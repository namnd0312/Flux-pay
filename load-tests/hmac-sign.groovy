// JSR223 PreProcessor shared by every scenario. Runs before each sampler and signs the
// gateway API calls the way a merchant does (see README "Merchant authentication"):
//
//   canonical = METHOD \n PATH \n X-Timestamp \n Idempotency-Key \n sha256hex(body)
//   X-Signature = hex(HMAC-SHA256(merchant secret, canonical))
//
// Two things are easy to get wrong in a load tool:
//   1. The bytes that are hashed must be the bytes that are sent. So the order id and the
//      idempotency key are generated HERE, once, into JMeter variables; a ${__UUID} written
//      directly in the request body would be evaluated again when the request is sent and
//      the signature would no longer match.
//   2. The path is signed without its query string.
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.apache.jmeter.protocol.http.control.Header
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase

if (!(sampler instanceof HTTPSamplerBase)) {
    return
}

if (sampler.getName() == 'create payment') {
    // burst=true: every thread sends the SAME order and key in the same loop iteration.
    boolean burst = props.get('burst') == 'true'
    String run = props.get('runId') ?: 'run'
    int iteration = vars.getIteration()
    vars.put('orderId', burst ? "ORD-${run}-${iteration}".toString() : "ORD-${UUID.randomUUID()}".toString())
    vars.put('idemKey', burst ? "key-${run}-${iteration}".toString() : UUID.randomUUID().toString())
    vars.put('status', 'PENDING')
    vars.put('polls', '0')
}

// The header manager is shared by every sampler of the thread and keeps what was added
// to it, so strip the previous request's signature first: a request that is not a
// merchant call (the mock provider's pay URL) must not carry merchant credentials.
def headers = sampler.getHeaderManager()
['X-Merchant-Id', 'X-Timestamp', 'X-Signature', 'Idempotency-Key'].each { headers.removeHeaderNamed(it) }

String rawPath = sampler.getPath()
if (!rawPath.startsWith('/api/')) {
    return // the mock provider's pay URL is not a merchant call
}
String path = rawPath.contains('?') ? rawPath.substring(0, rawPath.indexOf('?')) : rawPath
String method = sampler.getMethod()
String body = ''
if (sampler.getPostBodyRaw() && sampler.getArguments().getArgumentCount() > 0) {
    body = sampler.getArguments().getArgument(0).getValue()
}
String key = (method == 'POST' && path == '/api/v1/payments') ? vars.get('idemKey') : null
String timestamp = String.valueOf(System.currentTimeMillis())

String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2)
    for (byte b : bytes) {
        sb.append(String.format('%02x', b))
    }
    return sb.toString()
}

String bodyHash = toHex(MessageDigest.getInstance('SHA-256').digest(body.getBytes(StandardCharsets.UTF_8)))
String canonical = [method, path, timestamp, key ?: '', bodyHash].join('\n')
Mac mac = Mac.getInstance('HmacSHA256')
String secret = props.get('merchantSecret') ?: 'demo-secret-change-me'
mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), 'HmacSHA256'))
String signature = toHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)))

headers.add(new Header('X-Merchant-Id', props.get('merchantId') ?: 'demo-merchant'))
headers.add(new Header('X-Timestamp', timestamp))
headers.add(new Header('X-Signature', signature))
if (key != null) {
    headers.add(new Header('Idempotency-Key', key))
}
