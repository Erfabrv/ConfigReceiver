package com.example.configreceiver

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** یه وب‌سرور خیلی ساده که صفحه‌ی ارسال رو به گوشی نشون می‌ده و کانفیگ‌ها رو دریافت می‌کنه. */
class ConfigServer(
    private val port: Int,
    private val pin: String,
    private val onConfig: (String) -> Unit
) {
    @Volatile private var serverSocket: ServerSocket? = null

    fun start() {
        thread(isDaemon = true) {
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                while (!ss.isClosed) {
                    val client = ss.accept()
                    thread(isDaemon = true) { handle(client) }
                }
            } catch (_: Exception) { }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 10_000
                val input = s.getInputStream()
                val out = s.getOutputStream()

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')

                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }

                when {
                    method == "GET" && path == "/" ->
                        respond(out, 200, "text/html; charset=utf-8", PAGE)

                    method == "POST" && path == "/send" -> {
                        if (headers["x-pin"] != pin) {
                            respond(out, 403, "text/plain; charset=utf-8", "کد اشتباه است. کد روی تلویزیون رو وارد کن.")
                            return
                        }
                        val len = headers["content-length"]?.toIntOrNull() ?: 0
                        if (len <= 0 || len > 256 * 1024) {
                            respond(out, 400, "text/plain; charset=utf-8", "کانفیگ خالی یا خیلی بزرگه.")
                            return
                        }
                        val body = readBytes(input, len).toString(Charsets.UTF_8).trim()
                        onConfig(body)
                        respond(out, 200, "text/plain; charset=utf-8", "دریافت شد ✅")
                    }

                    else -> respond(out, 404, "text/plain; charset=utf-8", "Not found")
                }
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = StringBuilder()
        while (buf.length < 8192) {
            val b = input.read()
            if (b == -1) return if (buf.isEmpty()) null else buf.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.append(b.toChar())
        }
        return buf.toString()
    }

    private fun readBytes(input: InputStream, len: Int): ByteArray {
        val data = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(data, read, len - read)
            if (n == -1) break
            read += n
        }
        return data.copyOf(read)
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; else -> "Not Found"
        }
        out.write(
            ("HTTP/1.1 $code $status\r\nContent-Type: $type\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(bytes)
        out.flush()
    }

    companion object {
        private val PAGE = """
<!doctype html>
<html lang="fa" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>ارسال کانفیگ به تلویزیون</title>
<style>
  body{font-family:Tahoma,Vazirmatn,sans-serif;background:#0f1a24;color:#e8eef3;margin:0 auto;padding:22px;max-width:560px;line-height:1.7}
  h1{font-size:21px;margin:4px 0 18px}
  label{display:block;margin:16px 0 6px;color:#9fb3c4;font-size:14px}
  textarea,input{width:100%;box-sizing:border-box;background:#172633;color:#e8eef3;border:1px solid #2a3d4d;border-radius:12px;padding:12px;font-size:15px}
  textarea{height:190px;direction:ltr;text-align:left;font-family:monospace}
  input{font-size:22px;text-align:center;letter-spacing:6px}
  textarea:focus,input:focus{outline:2px solid #4fa3e0;border-color:transparent}
  button{width:100%;padding:15px;margin-top:18px;font-size:17px;border:0;border-radius:12px;background:#1f7ae0;color:#fff;font-family:inherit}
  button:active{background:#1560b5}
  #msg{margin-top:16px;text-align:center;min-height:1.7em}
</style>
</head>
<body>
<h1>📺 ارسال کانفیگ به اندروید باکس</h1>
<label for="cfg">کانفیگ‌ها رو اینجا پیست کن (هر خط یکی)</label>
<textarea id="cfg" placeholder="vless://...&#10;vmess://..."></textarea>
<label for="pin">کدی که روی تلویزیون نوشته شده</label>
<input id="pin" inputmode="numeric" maxlength="4">
<button id="send">ارسال به تلویزیون</button>
<div id="msg"></div>
<script>
  var fa='۰۱۲۳۴۵۶۷۸۹';
  function norm(v){return v.replace(/[۰-۹]/g,function(d){return fa.indexOf(d);}).trim();}
  var p=new URLSearchParams(location.search).get('pin');
  if(p) document.getElementById('pin').value=p;
  document.getElementById('send').onclick=function(){
    var c=document.getElementById('cfg').value.trim();
    var m=document.getElementById('msg');
    if(!c){m.textContent='اول کانفیگ رو پیست کن.';return;}
    m.textContent='در حال ارسال...';
    fetch('/send',{method:'POST',
      headers:{'Content-Type':'text/plain; charset=utf-8','X-Pin':norm(document.getElementById('pin').value)},
      body:c})
    .then(function(r){return r.text();})
    .then(function(t){m.textContent=t; if(t.indexOf('✅')>=0) document.getElementById('cfg').value='';})
    .catch(function(){m.textContent='اتصال برقرار نشد. گوشی و باکس باید به یک وای‌فای وصل باشن و برنامه روی باکس باز باشه.';});
  };
</script>
</body>
</html>
""".trimIndent()
    }
}
