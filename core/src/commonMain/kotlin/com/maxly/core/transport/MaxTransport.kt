package com.maxly.core.transport

import com.maxly.core.protocol.CmdType
import com.maxly.core.protocol.DefaultMessagePackCodec
import com.maxly.core.protocol.MessagePackCodec
import com.maxly.core.protocol.Opcode
import com.maxly.core.protocol.PROTOCOL_VERSION
import com.maxly.core.protocol.PacketHeader
import com.maxly.core.protocol.decodeHeader
import com.maxly.core.protocol.encodePacket
import com.maxly.core.protocol.decodePayloadPacket
import com.maxly.core.protocol.CompressionFormat
import com.maxly.core.protocol.encodePacketCompressed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration

/**
 * [TlsTransport] over a [RawConnection] from [factory]. All protocol logic is common code; the
 * platform part is only the factory (see [defaultConnectionFactory]).
 *
 * Behaviour follows kolibri `transport/client.rs`, `dispatcher.rs` and `session/manager.rs`:
 * - `seq`: [SeqCounter], 1, 2, ... 65535, 0, 1, ..., restarting at 1 on every connection;
 * - replies (`cmd` 1 OK, 2 NOT_FOUND, 3 ERROR) resolve the waiter with the same `seq`; replies
 *   nobody waits for are dropped; everything else goes to [pushes], except the server's own
 *   PING (opcode 1 with `cmd` 0): the transport answers it at once with `cmd` 1, the same `seq`
 *   and opcode and an empty body, as the Android app does (`vpc`), and does not publish it;
 * - requests time out after [TransportConfig.requestTimeout], counted from before the write (wait
 *   for the write lock, the write, the reply); a write still running at the deadline closes the
 *   connection so a blocked socket cannot hold the caller or later writers, and so does a timeout
 *   during which not a byte came from the server (a dead socket, as after iOS suspended the app).
 *   The connect (TCP + proxy + TLS) times out after [TransportConfig.connectTimeout];
 * - PING (opcode 1, `{"interactive": <pingInteractive>}`) right after the connection is up
 *   ([onConnected] done, i.e. after the handshake and `LOGIN`), then every
 *   [TransportConfig.pingInterval] (29 s, like the Android app's pinger `c4e`, which also pings
 *   first and then waits), fire-and-forget (the reply is dropped); the flag starts as
 *   [TransportConfig.pingInteractive] and [setPingInteractive] switches it on a live connection;
 * - a server `RECONNECT` (opcode 3, `{redirectHost: "host:port", tls}`) drops the connection
 *   and reconnects at once, to the given host when it is on [TransportConfig.redirectDomains]
 *   ([ServerRedirect]); an unsafe one is ignored. The outcome is in [lastRedirect];
 * - on a drop every pending request fails with [ConnectionClosedException]; with
 *   [TransportConfig.autoReconnect] the transport reconnects after [reconnectDelay] (3 s,
 *   doubling up to 96 s, ±10% jitter, like the Android app's `ConnectionBackoff`), resetting the
 *   attempt counter after each successful connection. A server `RECONNECT` (opcode 3) reconnects
 *   at once.
 *
 * - like kolibri's supervisor, a failed first [connect] is thrown to the caller and, with
 *   [TransportConfig.autoReconnect], retried in the background on the same schedule (an app
 *   started without network comes online by itself once the network is back).
 *
 * Difference from kolibri: reconnect lives here and not in the session, so the session layer
 * re-runs the opcode 6 handshake through [onConnected] on every (re)connect.
 *
 * @param onConnected runs after every successful TLS connect, before [state] becomes
 *   [ConnectionState.Connected]; [request] already works inside it (use it for the handshake). If
 *   it throws, the connection is dropped (and, on reconnect, retried).
 * @param scope where the reader, ping and reconnect coroutines run. Tests pass a `TestScope`'s
 *   `backgroundScope` to get virtual time.
 * @param random jitter source of [reconnectDelay]; tests pass a seeded one.
 */
class MaxTransport(
    config: TransportConfig,
    private val factory: ConnectionFactory = defaultConnectionFactory(),
    private val codec: MessagePackCodec = DefaultMessagePackCodec,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onConnected: (suspend (MaxTransport) -> Unit)? = null,
    private val random: Random = Random.Default,
) : TlsTransport {

    /**
     * Current configuration; replaced by [connect] with a non-null argument, and its `host` /
     * `port` by an accepted server `RECONNECT` ([lastRedirect]) for every later connect.
     */
    @kotlin.concurrent.Volatile
    var config: TransportConfig = config
        private set

    private val _lastRedirect = MutableStateFlow<ServerRedirect?>(null)

    /**
     * The last server `RECONNECT` (opcode 3) push and what was done with it (followed, or ignored
     * with a reason), `null` before the first. For logs and diagnostics; the push itself is not
     * published in [pushes].
     */
    val lastRedirect: StateFlow<ServerRedirect?> = _lastRedirect.asStateFlow()

    /** Set by an accepted `RECONNECT`: the next reconnect attempt goes out without the backoff delay. */
    @kotlin.concurrent.Volatile
    private var reconnectAtOnce = false

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _pushes = MutableSharedFlow<TransportPacket>(
        extraBufferCapacity = PUSH_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val pushes: SharedFlow<TransportPacket> = _pushes.asSharedFlow()

    /** Queues of the [reliablePushes] collectors (copy-on-write). */
    private val reliableQueues = MutableStateFlow<List<Channel<TransportPacket>>>(emptyList())

    /**
     * Every push, without loss: each collector gets its own unbounded queue, registered when the
     * collection starts and removed when it ends, and sees every push that arrives in between,
     * across reconnects. Unlike [pushes] nothing is dropped for a slow collector, so collect it
     * with fast, non-blocking code (the router applies events to its store here and hands them
     * to user handlers through its own queue). The socket reader never waits for a collector.
     */
    fun reliablePushes(): Flow<TransportPacket> = flow {
        val queue = Channel<TransportPacket>(Channel.UNLIMITED)
        reliableQueues.update { it + queue }
        try {
            for (packet in queue) emit(packet)
        } finally {
            reliableQueues.update { list -> list.filterNot { it === queue } }
            queue.close()
        }
    }

    private val rawChunks = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = PUSH_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Serializes connect / close. */
    private val lifecycleLock = Mutex()

    /** Guards [seq], [pending], [connection], [readerJob], [pingJob], [watchdogJob]. */
    private val stateLock = Mutex()

    /** Serializes socket writes so packets are not interleaved. */
    private val writeLock = Mutex()

    private val seq = SeqCounter()
    private val pending = PendingRequests()
    private var connection: RawConnection? = null

    /** Chunks read from the server so far (any connection): a request that heard none timed out on a dead socket. */
    @kotlin.concurrent.Volatile
    private var inboundChunks = 0L
    private var readerJob: Job? = null
    private var pingJob: Job? = null
    private var watchdogJob: Job? = null

    /** Bumped on every chunk read from the server; the read watchdog waits for it to change. */
    private val inboundActivity = MutableStateFlow(0L)

    /**
     * The `interactive` flag of every PING: `true` while the user is looking at the app (the MAX web
     * client sends "not idle"), `false` in the background. Starts as [TransportConfig.pingInteractive].
     */
    @kotlin.concurrent.Volatile
    var pingInteractive: Boolean = config.pingInteractive
        private set

    /**
     * Checks every outgoing request right before it is encoded ([OutboundGuard]); `null` sends
     * everything as is. A blocked request throws [OutboundBlockedException] without writing.
     */
    @kotlin.concurrent.Volatile
    var outboundGuard: OutboundGuard? = null

    /**
     * [payload] after [RefusedOpcodes] and [outboundGuard]; throws [OutboundBlockedException] for a
     * refused or blocked request.
     */
    private fun guarded(opcode: Int, payload: Any?): Any? {
        RefusedOpcodes.check(opcode)
        return when (val d = outboundGuard?.check(opcode, payload) ?: OutboundDecision.Pass) {
            OutboundDecision.Pass -> payload
            is OutboundDecision.Rewrite -> d.payload
            is OutboundDecision.Block -> throw OutboundBlockedException(opcode, d.reason)
        }
    }
    private var supervisorJob: Job? = null

    override fun receive(): Flow<ByteArray> = rawChunks.asSharedFlow()

    override suspend fun connect(config: TransportConfig?) {
        lifecycleLock.withLock {
            if (config != null) this.config = config
            if (supervisorJob?.isActive == true) return
            val reader = try {
                establish()
            } catch (e: Throwable) {
                // kolibri `supervise`: the first error goes to the caller, the retries go on
                if (this.config.autoReconnect && (e !is CancellationException || e is TimeoutCancellationException)) {
                    supervisorJob = scope.launch { supervise(null) }
                }
                throw e
            }
            supervisorJob = scope.launch { supervise(reader) }
        }
    }

    override suspend fun send(bytes: ByteArray) {
        val conn = stateLock.withLock { connection } ?: throw ConnectionClosedException("not connected")
        writeWithin(conn, bytes, -1, -1)
    }

    override suspend fun request(opcode: Opcode, payload: Any?): TransportPacket =
        request(opcode.value, payload)

    /** [request] for a raw opcode value (codes missing from [Opcode]). */
    suspend fun request(opcode: Int, payload: Any?): TransportPacket {
        val reply = requestRaw(opcode, payload)
        return when (reply.header.cmd) {
            CmdType.OK.value -> reply
            CmdType.ERROR.value -> {
                val e = ServerErrorException.from(reply)
                onServerError?.let { hook -> runCatching { hook(e) } }
                throw e
            }
            else -> throw NotFoundException(reply)
        }
    }

    /**
     * Called with every ERROR reply [request] turns into a [ServerErrorException], before it is
     * thrown to the caller (the session uses it to end the login on `login.token` and friends).
     * Must be quick and must not throw (an exception is dropped).
     */
    @kotlin.concurrent.Volatile
    var onServerError: ((ServerErrorException) -> Unit)? = null

    /**
     * Like [request], but returns the reply for any `cmd` (OK, NOT_FOUND or ERROR) instead of
     * mapping ERROR / NOT_FOUND to exceptions (kolibri `request_raw`). Only a lost connection, a
     * timeout or [outboundGuard] ([OutboundBlockedException], nothing written) throws.
     */
    suspend fun requestRaw(opcode: Int, payload: Any?): TransportPacket {
        val body = encodeBody(guarded(opcode, payload))
        val (conn, seqValue, waiter) = stateLock.withLock {
            val conn = connection ?: throw ConnectionClosedException("not connected")
            val s = seq.next()
            Triple(conn, s, pending.register(s))
        }
        val heard = inboundChunks
        try {
            // one deadline for the write lock, the write itself and the reply
            return withTimeout(config.requestTimeout) {
                timeoutWins {
                    write(conn, encodeRequest(seqValue, opcode, body))
                    waiter.await()
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Not a byte from the server for the whole timeout (no reply, no push, no ping reply):
            // the socket is dead without knowing it, as after iOS suspended the app. Closing it lets
            // the reader end and the supervisor reconnect; otherwise every next request on it would
            // wait out its own timeout too.
            if (inboundChunks == heard) withContext(NonCancellable) { closeIfCurrent(conn) }
            throw RequestTimeoutException(
                opcode, seqValue,
                "no reply to ${Opcode.nameOf(opcode)} (seq $seqValue) within ${config.requestTimeout}",
            )
        } finally {
            withContext(NonCancellable) { stateLock.withLock { pending.remove(seqValue, waiter) } }
        }
    }

    /**
     * Runs [block] inside `withTimeout`: if it fails because the deadline closed the connection
     * (the write then throws a connection error), report the timeout instead of that error.
     */
    private suspend inline fun <T> CoroutineScope.timeoutWins(block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        if (e !is CancellationException) ensureActive()
        throw e
    }

    /** Waiters still registered (tests). */
    internal suspend fun pendingCount(): Int = stateLock.withLock { pending.size }

    /**
     * Fire-and-forget request (typing, ping): sends [opcode] with the next `seq` and does not wait
     * for the reply. Returns the `seq`. Like [requestRaw] it passes [outboundGuard] first.
     */
    suspend fun sendRequest(opcode: Int, payload: Any?): Int {
        val body = encodeBody(guarded(opcode, payload))
        val (conn, seqValue) = stateLock.withLock {
            val conn = connection ?: throw ConnectionClosedException("not connected")
            conn to seq.next()
        }
        writeWithin(conn, encodeRequest(seqValue, opcode, body), opcode, seqValue)
        return seqValue
    }

    /**
     * Sets [pingInteractive] without sending anything: the value the next PINGs carry. For a
     * client that knows its state before the first connection (e.g. a saved ghost mode).
     */
    fun presetPingInteractive(interactive: Boolean) {
        pingInteractive = interactive
    }

    /**
     * Switches the PING `interactive` flag (kolibri `set_ping_interactive`): every later PING carries
     * [interactive], and when the flag changed and a connection is up one PING with it goes out at
     * once (fire-and-forget), so the server learns about it without waiting for the next tick.
     * Returns `true` when that PING was written. Never throws for a send error.
     */
    suspend fun setPingInteractive(interactive: Boolean): Boolean {
        val changed = pingInteractive != interactive
        pingInteractive = interactive
        if (!changed) return false
        return try {
            sendRequest(Opcode.PING.value, mapOf("interactive" to interactive))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
        }
    }

    override suspend fun close() {
        lifecycleLock.withLock {
            val supervisor = supervisorJob
            supervisorJob = null
            reconnectAtOnce = false
            // closing the socket first unblocks a platform read, then the jobs can finish
            dropConnection(ConnectionClosedException("transport closed"))
            supervisor?.cancelAndJoin()
            dropConnection(ConnectionClosedException("transport closed"))
            _state.value = ConnectionState.Disconnected
        }
    }

    // ── connection lifecycle ────────────────────────────────────────

    /** Opens one connection, starts reader + ping, runs [onConnected]. Returns the reader job. */
    private suspend fun establish(): Job {
        val cfg = config
        _state.value = ConnectionState.Connecting
        val conn = try {
            openWithFailover(cfg)
        } catch (e: Throwable) {
            _state.value = ConnectionState.Disconnected
            throw e
        }

        val reader = stateLock.withLock {
            connection = conn
            seq.reset()
            val r = scope.launch { readLoop(conn) }.also { readerJob = it }
            readIdleTimeout(cfg)?.let { limit -> watchdogJob = scope.launch { readWatchdog(conn, limit) } }
            r
        }
        try {
            onConnected?.invoke(this)
        } catch (e: Throwable) {
            dropConnection(ConnectionClosedException("onConnected failed", e))
            _state.value = ConnectionState.Disconnected
            throw e
        }
        stateLock.withLock {
            if (connection === conn && reader.isActive) {
                pingJob = scope.launch { pingLoop(conn, cfg.pingInterval) }
            }
        }
        if (reader.isActive) _state.value = ConnectionState.Connected
        return reader
    }

    /**
     * Opens a connection to the configured host and, when that fails (error or connect timeout),
     * to each of [failoverHosts] in order. Throws the primary host's error when every host failed;
     * the caller then waits out the usual backoff. Cancellation is never swallowed.
     */
    private suspend fun openWithFailover(cfg: TransportConfig): RawConnection {
        val proxy = cfg.proxyUrl?.let(ProxyConfig::parse)
        val tls = TlsOptions(cfg.insecureTls, cfg.trustMincifryCa, cfg.connectTimeout)
        val primary = FailoverHost(cfg.host, cfg.port)
        val targets = listOf(primary) + failoverHosts.filter { it != primary }
        var firstError: Throwable? = null
        for (target in targets) {
            try {
                return withTimeout(cfg.connectTimeout) { factory.open(target.host, target.port, tls, proxy) }
            } catch (e: TimeoutCancellationException) {
                if (firstError == null) {
                    firstError = ConnectTimeoutException("connect to ${target.host}:${target.port} timed out after ${cfg.connectTimeout}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (firstError == null) firstError = e
            }
        }
        throw firstError ?: ConnectionClosedException("no host to connect to")
    }

    /**
     * Hosts tried in order when connecting to the configured host fails (server config
     * `failover-hosts`, see [setFailoverHosts]). Empty by default.
     */
    @kotlin.concurrent.Volatile
    var failoverHosts: List<FailoverHost> = emptyList()
        private set

    /**
     * Replaces [failoverHosts] with [hosts] (`host` or `host:port`; a missing or bad port means the
     * configured port). Blank entries and duplicates are dropped. Takes effect on the next connect.
     */
    fun setFailoverHosts(hosts: List<String>) {
        failoverHosts = hosts.mapNotNull { FailoverHost.parse(it, config.port) }.distinct()
    }

    /**
     * Waits for the connection to drop, then reconnects with backoff while allowed. Without
     * [firstReader] (the first connect failed) it starts with the reconnect attempts.
     */
    private suspend fun supervise(firstReader: Job?) {
        var reader = firstReader
        while (true) {
            if (reader != null) {
                reader.join()
                dropConnection(ConnectionClosedException("connection lost"))
                _state.value = ConnectionState.Disconnected
                if (!config.autoReconnect) return
            }
            var attempt = 0
            while (true) {
                if (attempt == 0 && reconnectAtOnce) {
                    // the server asked for it (RECONNECT): no backoff
                    reconnectAtOnce = false
                } else {
                    delay(reconnectDelay(attempt, random))
                }
                attempt++
                try {
                    reader = establish()
                    break
                } catch (e: CancellationException) {
                    if (e is TimeoutCancellationException) continue
                    throw e
                } catch (e: Throwable) {
                    // stay Disconnected and try again after the next delay
                }
            }
        }
    }

    /**
     * Drops the live connection on purpose (e.g. the server said the session state is invalid):
     * the supervisor reconnects after the first backoff delay (so a server that keeps answering
     * the same way is not hammered) and the session runs its handshake and `LOGIN` again. Without
     * auto-reconnect the transport just ends up [ConnectionState.Disconnected]. Does nothing while
     * no connection is up.
     */
    suspend fun reconnect() {
        val conn = stateLock.withLock { connection } ?: return
        closeIfCurrent(conn)
    }

    /**
     * Read watchdog: closes [conn] when not a byte arrived from the server for [limit]. A socket
     * that died without a FIN or RST (sleep, network change, a NAT that forgot the flow) otherwise
     * keeps blocking the read while our PINGs pile up in the kernel buffer, and the supervisor
     * never learns that the connection is gone.
     */
    private suspend fun readWatchdog(conn: RawConnection, limit: Duration) {
        while (true) {
            val seen = inboundActivity.value
            val heard = withTimeoutOrNull(limit) { inboundActivity.first { it != seen } }
            if (heard == null) {
                closeIfCurrent(conn)
                return
            }
        }
    }

    /** Closes [conn] if it is still the live connection; the reader then sees the drop. */
    private suspend fun closeIfCurrent(conn: RawConnection) {
        if (stateLock.withLock { connection === conn }) runCatching { conn.close() }
    }

    private suspend fun dropConnection(cause: Throwable) {
        val (conn, reader, ping) = stateLock.withLock {
            val snapshot = Triple(connection, readerJob, pingJob)
            watchdogJob?.cancel()
            watchdogJob = null
            connection = null
            readerJob = null
            pingJob = null
            pending.failAll(cause)
            snapshot
        }
        ping?.cancel()
        runCatching { conn?.close() }
        reader?.cancel()
    }

    private suspend fun readLoop(conn: RawConnection) {
        val reassembler = PacketReassembler()
        val buffer = ByteArray(READ_CHUNK)
        try {
            while (true) {
                val n = conn.read(buffer, 0, buffer.size)
                if (n < 0) break
                if (n == 0) continue
                inboundChunks++
                inboundActivity.value = inboundActivity.value + 1
                rawChunks.tryEmit(buffer.copyOf(n))
                for (frame in reassembler.feed(buffer, 0, n)) {
                    // a followed RECONNECT ends this connection: the supervisor reconnects
                    if (!dispatch(conn, frame)) return
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // I/O error or framing overflow: treat as a drop, like kolibri's reader task
        }
    }

    /**
     * Routes one complete packet: a reply to its waiter, a server PING to [answerServerPing], a
     * server `RECONNECT` to [followRedirect], anything else to [pushes]. Returns `false` when the
     * reader must stop because the connection is being replaced.
     */
    private suspend fun dispatch(conn: RawConnection, frame: ByteArray): Boolean {
        val header = try {
            decodeHeader(frame)
        } catch (e: IllegalArgumentException) {
            return true
        }
        if (isServerPing(header)) {
            answerServerPing(conn, header)
            return true
        }
        val decoded = runCatching { decodePayloadPacket(frame, codec) }
        if (isReply(header.cmd)) {
            val waiter = stateLock.withLock { pending.take(header.seq) } ?: return true
            decoded.fold(
                onSuccess = { (h, payload) -> waiter.complete(TransportPacket(h, payload)) },
                onFailure = { waiter.completeExceptionally(TransportException("cannot decode reply seq ${header.seq}", it)) },
            )
        } else if (header.opcodeValue == Opcode.RECONNECT.value) {
            val payload = decoded.getOrNull()?.second
            return !followRedirect(payload)
        } else {
            // compressed pushes are decompressed by decodePayloadPacket (LZ4 block / LZ4 frame / Zstd);
            // undecodable ones (unknown flag, corrupt body, bad MessagePack) are skipped, like kolibri
            decoded.onSuccess { (h, payload) ->
                val packet = TransportPacket(h, payload)
                reliableQueues.value.forEach { it.trySend(packet) }
                _pushes.tryEmit(packet)
            }
        }
        return true
    }

    /**
     * Handles a server `RECONNECT` push ([ServerRedirect]): records it in [lastRedirect] and, when
     * it may be followed, switches [config] to the new host and port (if any), fails the pending
     * requests and arms [reconnectAtOnce]. Returns `true` when the connection must end now; the
     * supervisor then reconnects without delay (the session runs its handshake and `LOGIN` again).
     */
    private suspend fun followRedirect(payload: Any?): Boolean {
        val cfg = config
        var r = ServerRedirect.evaluate(payload, cfg.redirectDomains)
        if (r.accepted && !cfg.autoReconnect) {
            r = r.copy(host = null, port = null, accepted = false, reason = "auto-reconnect is off")
        }
        _lastRedirect.value = r
        if (!r.accepted) return false
        if (r.host != null && r.port != null) config = cfg.copy(host = r.host, port = r.port)
        reconnectAtOnce = true
        // waiters must not sit out their timeout on a connection that is going away
        stateLock.withLock { pending.failAll(ConnectionClosedException("server asked to reconnect")) }
        return true
    }

    /**
     * Answers a server PING (opcode 1, `cmd` 0) on [conn]: `cmd` 1 (OK), the same `seq` and
     * opcode, empty body, like the Android app (`vpc`, `new bld(1, seq, opcode, empty)`). The
     * server's body, if any, is ignored.
     *
     * The reply is not a request, so [outboundGuard] does not see it: ghost mode rewrites the
     * `interactive` flag of the PINGs this client sends, and an empty acknowledgement carries no
     * flag and tells the server nothing about the user (it only proves the socket is alive,
     * which the open connection already does). Passing it through the guard would turn it into
     * a body `{interactive: false}`, which the official client never sends in a reply.
     *
     * The write runs in its own coroutine so a busy write lock never stalls the reader; a failed
     * write is dropped (the connection is then broken anyway and the reader sees it).
     */
    private fun answerServerPing(conn: RawConnection, header: PacketHeader) {
        val reply = encodePacket(
            PacketHeader(PROTOCOL_VERSION, CmdType.OK.value, header.seq, header.opcode, 0, compressed = false),
            ByteArray(0),
        )
        scope.launch {
            try {
                writeWithin(conn, reply, header.opcodeValue, header.seq)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // the connection is gone; nothing to answer on
            }
        }
    }

    /**
     * PING at once, then every [interval]; started once [onConnected] (handshake, `LOGIN`) is done.
     * A failed write closes [conn], so the supervisor reconnects instead of leaving a connection
     * that no longer pings.
     */
    private suspend fun pingLoop(conn: RawConnection, interval: Duration) {
        if (interval == Duration.INFINITE || !interval.isPositive()) return
        while (true) {
            try {
                sendRequest(Opcode.PING.value, mapOf("interactive" to pingInteractive))
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutboundBlockedException) {
                // a guard may hold a tick back; the loop goes on
            } catch (e: Throwable) {
                withContext(NonCancellable) { closeIfCurrent(conn) }
                return
            }
            delay(interval)
        }
    }

    /** [write] bounded by [TransportConfig.requestTimeout] (lock wait included). */
    private suspend fun writeWithin(conn: RawConnection, bytes: ByteArray, opcode: Int, seqValue: Int) {
        try {
            withTimeout(config.requestTimeout) { timeoutWins { write(conn, bytes) } }
        } catch (e: TimeoutCancellationException) {
            val what = if (opcode < 0) "raw bytes" else "${Opcode.nameOf(opcode)} (seq $seqValue)"
            throw RequestTimeoutException(opcode, seqValue, "could not write $what within ${config.requestTimeout}")
        }
    }

    /**
     * Writes under [writeLock]. The caller's deadline covers the wait for the lock and the write.
     * A platform write may block and ignore cancellation (a JVM `OutputStream.write` is not bounded
     * by the read timeout), so when the caller is cancelled (timeout) while the write is still
     * running, [conn] is closed: that unblocks the write, and a half-written frame would corrupt
     * the stream anyway. The reader then sees the drop and the transport reconnects as usual.
     */
    private suspend fun write(conn: RawConnection, bytes: ByteArray) {
        try {
            writeLock.withLock { abortableWrite(conn, bytes) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportException) {
            throw e
        } catch (e: Throwable) {
            throw ConnectionClosedException("write failed: ${e.message}", e)
        }
    }

    private suspend fun abortableWrite(conn: RawConnection, bytes: ByteArray) = coroutineScope {
        var finished = false
        val guard = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished) withContext(NonCancellable) { runCatching { conn.close() } }
            }
        }
        try {
            conn.write(bytes)
        } finally {
            finished = true
            guard.cancel()
        }
    }

    /**
     * Frames a request like kolibri: bodies of [com.maxly.core.protocol.COMPRESSION_THRESHOLD] bytes
     * or more go out as LZ4 block with the ratio-hint flag, unless compression would not shrink them.
     */
    private fun encodeRequest(seq: Int, opcode: Int, body: ByteArray): ByteArray =
        encodePacketCompressed(PROTOCOL_VERSION, CmdType.REQUEST.value, seq, opcode.toShort(), body, CompressionFormat.LZ4_BLOCK)

    /** Same body rule as [com.maxly.core.protocol.encodePayloadPacket]: `null` gives an empty body. */
    private fun encodeBody(payload: Any?): ByteArray = if (payload == null) ByteArray(0) else codec.encode(payload)

    companion object {
        /** kolibri `PUSH_CHANNEL_CAPACITY`. */
        const val PUSH_BUFFER_CAPACITY: Int = 256

        /** kolibri `READ_CHUNK`: 64 KiB. */
        const val READ_CHUNK: Int = 64 * 1024

        /**
         * How long the read watchdog waits for any byte from the server: one ping interval plus
         * the request timeout (the reply to our PING, or the server's own PING, is late by then).
         * `null` (no watchdog) when the ping loop is off.
         */
        internal fun readIdleTimeout(cfg: TransportConfig): Duration? =
            if (cfg.pingInterval == Duration.INFINITE || !cfg.pingInterval.isPositive()) null
            else cfg.pingInterval + cfg.requestTimeout

        private fun isReply(cmd: Byte): Boolean =
            cmd == CmdType.OK.value || cmd == CmdType.NOT_FOUND.value || cmd == CmdType.ERROR.value

        /** A keepalive the server sends on its own: opcode PING with `cmd` 0 (a request, not a reply). */
        private fun isServerPing(header: PacketHeader): Boolean =
            header.cmd == CmdType.REQUEST.value && header.opcodeValue == Opcode.PING.value
    }
}

/** One `failover-hosts` entry: where [MaxTransport] connects when the primary host is unreachable. */
data class FailoverHost(val host: String, val port: Int) {
    companion object {
        /** Parses `host` or `host:port`; `null` for a blank entry. */
        fun parse(entry: String, defaultPort: Int): FailoverHost? {
            val e = entry.trim()
            if (e.isEmpty()) return null
            val i = e.lastIndexOf(':')
            if (i > 0) {
                val port = e.substring(i + 1).toIntOrNull()
                if (port != null && port in 1..65535) return FailoverHost(e.substring(0, i), port)
            }
            return FailoverHost(e, defaultPort)
        }
    }
}
