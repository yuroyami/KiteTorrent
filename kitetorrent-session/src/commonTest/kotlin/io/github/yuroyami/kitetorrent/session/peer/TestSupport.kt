package io.github.yuroyami.kitetorrent.session.peer

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * A fully in-memory [ByteStream] duplex for driving a [PeerConnection] in tests with
 * no sockets. The two directions are independent:
 *  - [inbound] is the byte stream the connection *reads*. Preload it with bytes the
 *    fake "peer" sends (handshake, messages). [readExactly] serves from here.
 *  - [written] accumulates everything the connection *writes*, so a test can assert
 *    on the exact frames it emitted.
 *
 * Crucially the inbound bytes are all present up front, so [readExactly] never
 * actually suspends as long as enough bytes were preloaded; this lets the suspend
 * functions complete synchronously under [runSuspendTest]. Reading past the end
 * throws [EndOfStream], which models a peer disconnect.
 */
class FakeDuplex(inbound: ByteArray = ByteArray(0)) : ByteStream {
    private var inbound: ByteArray = inbound
    private var readPos: Int = 0

    /** Everything the connection-under-test has written, concatenated. */
    val written: ByteArrayOut = ByteArrayOut()

    override val remoteAddress: String = "fake:0"

    /** Append more bytes for the connection to read (e.g. a message after the handshake). */
    fun feed(bytes: ByteArray) {
        inbound = inbound + bytes
    }

    override suspend fun readExactly(n: Int): ByteArray {
        if (readPos + n > inbound.size) throw EndOfStream
        val out = inbound.copyOfRange(readPos, readPos + n)
        readPos += n
        return out
    }

    override suspend fun write(bytes: ByteArray) {
        written.append(bytes)
    }
}

/** Thrown by [FakeDuplex.readExactly] when the preloaded inbound bytes run out. */
object EndOfStream : RuntimeException("fake duplex: end of stream")

/** A tiny growable byte sink (no java.io dependency, so it stays multiplatform). */
class ByteArrayOut {
    private var buf = ByteArray(0)
    fun append(bytes: ByteArray) { buf += bytes }
    fun toByteArray(): ByteArray = buf.copyOf()
    val size: Int get() = buf.size
}

/**
 * Run a suspend [block] that is expected to complete synchronously, i.e. it never
 * suspends on anything but already-satisfiable work (our [FakeDuplex] never parks).
 * This avoids a dependency on kotlinx-coroutines-test (`runTest`), which is not on
 * the session module's test classpath, while still exercising the real suspend code
 * paths in [PeerConnection].
 *
 * If [block] *does* suspend (e.g. it tries to read more bytes than were preloaded and
 * the fake throws instead of parking), the captured result/exception is surfaced.
 * A genuine never-completing suspension fails the test with [IllegalStateException].
 */
fun runSuspendTest(block: suspend () -> Unit) {
    var done = false
    var failure: Throwable? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result ->
        done = true
        failure = result.exceptionOrNull()
    })
    check(done) { "suspend block did not complete synchronously (it parked on a real suspension)" }
    failure?.let { throw it }
}

/** [runSuspendTest] variant that returns a value computed by [block]. */
fun <T> runSuspendTestResult(block: suspend () -> T): T {
    var holder: Result<T>? = null
    val body: suspend () -> Unit = { holder = Result.success(block()) }
    var done = false
    var failure: Throwable? = null
    body.startCoroutine(Continuation<Unit>(EmptyCoroutineContext) { result ->
        done = true
        failure = result.exceptionOrNull()
    })
    check(done) { "suspend block did not complete synchronously (it parked on a real suspension)" }
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return (holder as Result<T>).getOrThrow()
}
