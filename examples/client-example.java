import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 极简 RESP 客户端示例：等待 Tiering-KV 服务端可用后运行。
 * 用法：java examples/client-example.java
 */
public class ClientExample {

    public static void main(String[] args) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", 6379);
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {
            // RESP: *1\r\n$4\r\nPING\r\n
            out.write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            byte[] response = in.readNBytes(128);
            System.out.print(new String(response, StandardCharsets.UTF_8));
        }
    }
}
