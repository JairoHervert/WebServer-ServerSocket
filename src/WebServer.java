import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class WebServer {
   
   int PORT = 8000;
   ServerSocket serverSocket;
   
   
   class Handler implements Runnable {
      
      protected Socket socket;
      DataOutputStream dataOutput;
      DataInputStream dataInput;
      
      // asignar el socket recibido a la variable socket del objeto
      public Handler(Socket _socket) {
         this.socket = _socket;
      }
      
      public void run() {
         try {
            dataOutput = new DataOutputStream(socket.getOutputStream());
            dataInput = new DataInputStream(socket.getInputStream());
            
            byte[] buffer = new byte[32768];    // 32 KB
            int bytesRead = dataInput.read(buffer);
            
            // Como postman (y algunos navegadores) envían una petición adicional por el keep-alive la desactivamos por ahora
            if (bytesRead < 1) {
               System.out.println("Conexión de keep-alive detectada. Cerrando conexión sin datos...");
               socket.close();
               return;
            }
            
            // Convertimos los bytes recibidos a una cadena
            String request = new String(buffer, 0, bytesRead);
            
            System.out.println("Tamaño de la petición: " + bytesRead);
            System.out.println("Petición recibida: \n" + "\u001B[33m" + request + "\u001B[0m");
            
            // Obtenemos el metodo, recurso y protocolo de la petición HTTP (que viene en la primera línea del request)
            String[] lines = request.split("\r\n");
            String firstLine = lines[0];
            
            // Dividimos la primera línea en sus partes (metodo, recurso y protocolo)
            String[] parts = firstLine.split(" ");
            String method = parts[0];
            String resource = parts[1];
            String protocol = parts[2];
            
            // Si la petición no tiene 3 partes, entonces es una solicitud HTTP mal formada
            if (parts.length != 3) {
               System.err.println("Solicitud HTTP mal formada.");
               String badRequestResponse = "HTTP/1.1 400 Bad Request\r\n"
                       + "Content-Type: text/plain\r\n"
                       + "Content-Length: 15\r\n"
                       + "\r\n"
                       + "Solicitud inválida";
               dataOutput.write(badRequestResponse.getBytes(StandardCharsets.UTF_8));
               return;
            }
            
            
            System.out.println("Método: " + method);
            System.out.println("Recurso: " + resource);
            System.out.println("Protocolo: " + protocol);
            
            String response;
            
            switch (method) {
               case "GET":
                  response = "HTTP/1.1 200 OK\r\n"
                          + "Content-Type: text/plain\r\n"
                          + "Content-Length: 27\r\n"
                          + "\r\n"
                          + "Hello World! desde un GET";
                  break;
               
               case "POST":
                  response = "HTTP/1.1 200 OK\r\n"
                          + "Content-Type: text/plain\r\n"
                          + "Content-Length: 28\r\n"
                          + "\r\n"
                          + "Hello World! desde un POST";
                  break;
               
               case "PUT":
                  response = "HTTP/1.1 200 OK\r\n"
                          + "Content-Type: text/plain\r\n"
                          + "Content-Length: 27\r\n"
                          + "\r\n"
                          + "Hello World! desde un PUT";
                  break;
               
               case "DELETE":
                  response = "HTTP/1.1 200 OK\r\n"
                          + "Content-Type: text/plain\r\n"
                          + "Content-Length: 30\r\n"
                          + "\r\n"
                          + "Hello World! desde un DELETE";
                  break;
               
               default:
                  response = "HTTP/1.1 405 Method Not Allowed\r\n"
                          + "Content-Type: text/plain\r\n"
                          + "Content-Length: 22\r\n"
                          + "\r\n"
                          + "Método no permitido";
                  break;
            }

            dataOutput.write(response.getBytes(StandardCharsets.UTF_8));
            dataOutput.flush();
            
         } catch (IOException e) {
            throw new RuntimeException(e);
         } finally {
            try {
               socket.close();
            } catch (IOException e) {
               e.printStackTrace();
            }
         }
      }
   }
   
   
   // Constructor
   public WebServer() throws IOException {
      System.out.println("\u001B[32mIniciando servidor web...\u001B[0m");
      this.serverSocket = new ServerSocket(PORT);
      System.out.println("Servidor web iniciado en el puerto \u001B[32m" + PORT + "\u001B[0m");
      System.out.println("\u001B[34mEsperando conexiones...\n\u001B[0m");
      
      while (true) {
         Socket socket = serverSocket.accept();
         System.out.println("Conexión aceptada desde \u001B[35m" + socket.getInetAddress() + "\u001B[0m");
         Handler handler = new Handler(socket);
         new Thread(handler).start();
      }
      
   }
   
   public static void main(String[] args) throws IOException {
      new WebServer();
   }
}
