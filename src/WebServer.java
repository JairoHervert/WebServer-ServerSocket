import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebServer {
   
   int PORT = 8000;
   private static final int THREAD_POOL_SIZE = 10; // Tamaño del pool de hilos
   private ServerSocket serverSocket;
   private ExecutorService threadPool;
   
   // Tabla de mime types
   private static final Map<String, String> MIME_TYPES = new HashMap<>() {{
      put("txt", "text/plain");
      put("html", "text/html");
      put("htm", "text/html");
      put("css", "text/css");
      put("js", "text/javascript");
      put("json", "application/json");
      put("xml", "application/xml");
      put("jpg", "image/jpeg");
      put("jpeg", "image/jpeg");
      put("png", "image/png");
      put("gif", "image/gif");
      put("ico", "image/x-icon");
      put("pdf", "application/pdf");
      put("zip", "application/zip");
      put("doc", "application/msword");
      put("xls", "application/vnd.ms-excel");
      put("ppt", "application/vnd.ms-powerpoint");
   }};
   
   // Tabla de códigos de estado HTTP y sus mensajes
   private static final Map<Integer, String> HTTP_STATUS_CODES = new HashMap<>() {{
      put(200, "OK");
      put(400, "Bad Request");
      put(404, "Not Found");
      put(405, "Method Not Allowed");
   }};
   
   
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
            System.out.println("Ejecutando el hilo: " + Thread.currentThread().getName());
            
            // Obtenemos el metodo, recurso y protocolo de la petición HTTP (que viene en la primera línea del request)
            String[] lines = request.split("\r\n");
            String firstLine = lines[0];
            
            // Dividimos la primera línea en sus partes (metodo, recurso y protocolo)
            String[] parts = firstLine.split(" ");
            
            String method = parts[0].toUpperCase();
            String resource = parts[1];
            String protocol = parts[2];
            
            // Si la petición no tiene 3 partes, entonces es una solicitud HTTP mal formada
            if (parts.length != 3) {
               System.err.println("Solicitud HTTP mal formada.");
               
               String badRequestResponse = createHead(400, "text/plain", 0);
               badRequestResponse += "Solicitud HTTP mal formada";
               
               dataOutput.write(badRequestResponse.getBytes(StandardCharsets.UTF_8));
               return;
            }
            
            System.out.println("Método: " + method);
            System.out.println("Recurso: " + resource);
            System.out.println("Protocolo: " + protocol);
            
            String response = "";
            String body = "";
            
            switch (method) {
               case "GET": // Manejar los casos: envio de archivos asi como de texto plano, mime type, paso de parametros y tal vez redireccionamiento
                  response = GETHandler(resource, dataOutput, body, response);
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
               dataOutput.close();
               socket.close();
            } catch (IOException e) {
               e.printStackTrace();
            }
         }
      }
   }
   
   // Metodo para obtener parametros de una petición. Recibe una cadena de formato "nombre=valor&nombre2=valor2"
   public Map<String, String> getParameters(String parameters) {
      Map<String, String> params = new HashMap<>();
      
      String[] pairs = parameters.split("&");
      
      for (String pair : pairs) {
         String[] keyValue = pair.split("=");
         
         // Si el parámetro tiene valor en cierta key, se agrega al mapa
         // Si no tiene valor, se agrega a su key una cadena vacía
         if (keyValue.length == 2) {
            params.put(keyValue[0], keyValue[1]);
         } else {
            params.put(keyValue[0], "");
         }
      }
      
      return params;
   }
   
   public String GETHandler(String resource, DataOutputStream dataOutput, String body, String response) {
      
      // Si la petición contiene parámetros
      if (resource.contains("?")) {
         System.out.println("Petición con parámetros");
         resource = resource.substring(resource.indexOf("?") + 1);   // Eliminar el recurso de la petición
         
         // Obtener los parámetros de la petición
         Map<String, String> parameters = getParameters(resource);
         
         // Agregar los parámetros al cuerpo de la respuesta
         for (Map.Entry<String, String> entry : parameters.entrySet()) {
            //System.out.println("Parámetro: " + entry.getKey() + " Tiene: " + entry.getValue());
            body += entry.getKey() + ": " + entry.getValue() + "\n";
         }
         
         // Crear la respuesta HTTP
         response = createHead(200, "text/plain", body.length());
         response += body;
         return response;
      }
      
      // Si no hay parámetros, se envía el archivo solicitado o el index.html
      if (resource.equals("/") || resource.equals("/index.html") || resource.equals("/index.htm") || resource == null) {
         // Enviar el archivo index.html
         SendFile("index.html", dataOutput);

      } else {
         resource = resource.substring(1); // Eliminar la barra inicial
         System.out.println("Recurso solicitado: " + resource);
         File file = new File(resource);
         
         // si el archivo existe y el ultimo caracter del recurso es No es un slash entonces se envia el archivo
         if (file.exists() && file.isFile() && resource.charAt(resource.length() - 1) != '/') {
            // Enviar el archivo
            SendFile(resource, dataOutput);
            
         } else if (file.exists() && file.isDirectory() && resource.charAt(resource.length() - 1) == '/') { // Si el recurso es un directorio y termina en /
            // Obtener la lista de archivos del directorio
            String[] fileNames = file.list();
            
            body += "Archivos en el directorio:\n";
            for (String fileName : fileNames) {
               body += fileName + "\n";
            }
            
            // Eliminar acentos y caracteres especiales de body para evitar problemas con el envio de la respuesta
            body = Normalizer.normalize(body, Normalizer.Form.NFD);
            body = body.replaceAll("[^\\p{ASCII}]", "");
            
            response = createHead(200, "text/plain", body.length());
            response += body;
            
         } else if (file.exists() && file.isDirectory() && resource.charAt(resource.length() - 1) != '/') { // Si el recurso es un directorio y no termina en /
            // Simulación de redireccionamiento
            response = "HTTP/1.1 301 Moved Permanently\r\n"
                    + "Location: " + resource + "/\r\n"  // Redireccionar al directorio agregando la barra final
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: 0\r\n"
                    + "\r\n";
         } else {
            System.out.println("Archivo no encontrado: " + file.getName());
            body = "Archivo o recurso no encontrado";
            response = createHead(404, "text/plain", body.length());
            response += body;
         }
      }
      
      //System.out.println("Respuesta:" + response);
      return response;
   }
   
   // Metodo para crear una respuesta HTTP (cabecera)
   public String createHead(int statusCode, String mimeType, long fileSize) {
      
      String statusMessage = HTTP_STATUS_CODES.get(statusCode);
      
      return "HTTP/1.1 " + statusCode + " " + HTTP_STATUS_CODES.get(statusCode) + "\r\n"
              + "Server: Hervert Server/1.0\r\n"
              + "Date: " + new Date() + "\r\n"
              + "Content-Type: " + mimeType + "\r\n"
              + "Content-Length: " + fileSize + "\r\n"
              + "Connection: close\r\n"
              + "\r\n";
   }
   
   // Metodo para enviar un archivo al cliente (GET)
   public void SendFile (String fileToSend, DataOutputStream dataOutput) {
      try {
         int bytesRead = 0;
         byte[] buffer = new byte[1024];
         
         DataInputStream fileInput = new DataInputStream(new FileInputStream(fileToSend));
         File file = new File(fileToSend);
         
         long fileSize = file.length();
         
         // Obtener el nombre y la extensión del archivo, además del mime type
         String[] fileNameAndExtension = fileToSend.split("\\.");
         String fileName = fileNameAndExtension[0];
         String fileExtension = fileNameAndExtension[1];
         String mimeType = MIME_TYPES.get(fileExtension);
         
         System.out.println("fileName = " + fileName);
         System.out.println("fileExtension = " + fileExtension);
         System.out.println("Archivo encontrado: " + file.getName());
         System.out.println("Mime type: " + mimeType);
         
         // Crear la respuesta HTTP
         String response = createHead(200, mimeType, file.length());
         
         // Enviar la respuesta HTTP sin el archivo
         dataOutput.write(response.getBytes(StandardCharsets.UTF_8));
         dataOutput.flush();
         
         // Enviar el archivo en bloques de 1024 bytes
         while ((bytesRead = fileInput.read(buffer)) != -1) {
            dataOutput.write(buffer, 0, bytesRead);
            dataOutput.flush();
         }
         
         dataOutput.close();
         fileInput.close();
         
      } catch (IOException e) {
         e.printStackTrace();
      }
   }
   
   
   // Constructor
   public WebServer() throws IOException {
      System.out.println("\u001B[32mIniciando servidor web...\u001B[0m");
      
      // Crear el socket del servidor y el pool de hilos
      this.serverSocket = new ServerSocket(PORT);
      this.threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
      
      System.out.println("Servidor web iniciado en el puerto \u001B[32m" + PORT + "\u001B[0m");
      System.out.println("\u001B[34mEsperando conexiones...\n\u001B[0m");
      
      while (true) {
         Socket socket = serverSocket.accept();
         System.out.println("Conexión aceptada desde \u001B[35m" + socket.getInetAddress() + "\u001B[0m");
         
         // Asigna la conexión aceptada a un hilo del pool
         threadPool.execute(new Handler(socket));
      }
   }
   
   public static void main(String[] args) throws IOException {
      new WebServer();
   }
}
