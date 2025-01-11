import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebServer {
   private static final int PORT = 8000;
   private static final int THREAD_POOL_SIZE = 4; // Tamaño de la alberca de hilos
   
   // Tabla de mime types y códigos de estado HTTP
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
      put("mp3", "audio/mpeg");
      put("tex", "application/x-tex");
   }};
   private static final Map<Integer, String> HTTP_STATUS_CODES = new HashMap<>() {{
      put(200, "OK");
      put(301, "Moved Permanently");
      put(302, "Found");
      put(400, "Bad Request");
      put(403, "Forbidden");
      put(404, "Not Found");
      put(405, "Method Not Allowed");
   }};
   
   // Constructor de la clase
   public WebServer() throws IOException {
      try {
         // Creamos el selector de canales
         Selector selector = Selector.open();
         
         // Creamos el ServerSocketChannel (en modo no bloqueante)
         ServerSocketChannel serverChannel = ServerSocketChannel.open();
         serverChannel.configureBlocking(false);
         serverChannel.bind(new InetSocketAddress(PORT));
         // Registrar el canal para aceptar conexiones
         serverChannel.register(selector, SelectionKey.OP_ACCEPT);
         
         // Pool de hilos con tamaño fijo
         ExecutorService threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
         
         System.out.println("Servidor iniciado en el puerto " + PORT);
         
         // Procesar eventos
         while (true) {
            
            selector.select();
            
            // Obtenemos las claves de los canales con eventos listos
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
            
            while (keyIterator.hasNext()) {
               SelectionKey key = keyIterator.next();
               keyIterator.remove(); // Evitar procesarla más de una vez
               
               if (key.isAcceptable()) {
                  acceptConnection(key);
               }
               else if (key.isReadable()) {
                  // Quitar interés en lectura momentáneamente para evitar lecturas duplicadas. Asignaremos un hilo para procesar la solicitud.
                  key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
                  threadPool.execute(new Worker(key));
               }
            }
         }
      } catch (IOException e) {
         e.printStackTrace();
      }
   }
   
   public static void main(String[] args) {
      try {
         new WebServer();
      } catch (IOException e) {
         e.printStackTrace();
      }
   }
   
   // Acepta una nueva conexión y la registra para lectura.
   private static void acceptConnection(SelectionKey key) throws IOException {
      // key.channel() es el ServerSocketChannel que lanzó el evento OP_ACCEPT
      ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
      
      // Aceptar la conexión, la configuramos como no bloqueante y la registramos para lectura
      SocketChannel clientChannel = serverChannel.accept();
      clientChannel.configureBlocking(false);
      clientChannel.register(key.selector(), SelectionKey.OP_READ);
      
      System.out.println("Nueva conexión aceptada desde " + clientChannel.getRemoteAddress());
   }
   
   // Clase Worker: se encarga de leer la petición, procesarla y enviar una respuesta.
   private static class Worker implements Runnable {
      private final SelectionKey key;
      
      public Worker(SelectionKey key) {
         this.key = key;
      }
      
      @Override
      public void run() {
         System.out.println("Worker ejecutado en hilo " + Thread.currentThread().getName());
         SocketChannel clientChannel = (SocketChannel) key.channel();
         try {
            // 1. Leer datos del canal
            ByteBuffer bufferByte = ByteBuffer.allocate(2048);
            ByteArrayOutputStream bufferBAOS = new ByteArrayOutputStream();
            
            int bytesRead;
            while ((bytesRead = clientChannel.read(bufferByte)) > 0) {
               bufferByte.flip();
               bufferBAOS.write(bufferByte.array(), 0, bufferByte.limit());
               bufferByte.clear();
            }
            
            if (bytesRead == -1) {
               System.out.println("Conexión cerrada por el cliente.");
               clientChannel.close();
               return;
            }
            
            // 2. Procesar la solicitud HTTP (básico)
            ByteBuffer buffer = ByteBuffer.wrap(bufferBAOS.toByteArray());
            String request = new String(buffer.array(), 0, buffer.limit(), StandardCharsets.UTF_8);
            System.out.println("Solicitud recibida:\n" + request);
            
            // Separamos la solicitud en dos partes: las cabeceras y el cuerpo
            String[] requestParts = request.split("\r\n\r\n", 2);
            String headers = requestParts[0];
            String body = requestParts.length > 1 ? requestParts[1] : "";
            
            String[] headerLines = headers.split("\r\n");   // separa todas las cabeceras en String individuales
            
            // verificamos si la primera línea de la solicitud es válida, verificando si contiene 3 partes
            if (headerLines[0].split(" ").length != 3) {
               System.err.println("Solicitud HTTP mal formada.");
               // enviar una respuesta de error 400 Bad Request
               clientChannel.close();
               return;
            }
            
            // Obtenemos el método
            String method = headerLines[0].split(" ")[0];
            String resource = URLDecoder.decode(headerLines[0].split(" ")[1], StandardCharsets.UTF_8);
            String responseBody = "";
            String httpResponse = "";
            
            switch (method) {
               case "GET":
                  httpResponse = getHandler(resource, clientChannel);
                  break;
               case "POST":
                  responseBody = "Hello desde un POST del servidor!";
                  httpResponse = ""
                          + "HTTP/1.1 201 OK\r\n"
                          + "Content-Type: text/plain\r\n"
                          + "Content-Length: " + responseBody.length() + "\r\n"
                          + "Connection: close\r\n"
                          + "\r\n"
                          + responseBody;
                  
                  break;
               
               case "PUT":
                  responseBody = "Hello desde un PUT del servidor!";
                  httpResponse = ""
                          + "HTTP/1.1 200 OK\r\n"
                          + "Content-Type: text/plain\r\n"
                          + "Content-Length: " + responseBody.length() + "\r\n"
                          + "Connection: close\r\n"
                          + "\r\n"
                          + responseBody;
                  
                  break;
                  
               case "DELETE":
                  responseBody = "Hello desde un DELETE del servidor!";
                  httpResponse = ""
                          + "HTTP/1.1 200 OK\r\n"
                          + "Content-Type: text/plain\r\n"
                          + "Content-Length: " + responseBody.length() + "\r\n"
                          + "Connection: close\r\n"
                          + "\r\n"
                          + responseBody;
                  break;
                  
                  
               case "HEAD":
                  System.out.println("Si entre al head");
                  httpResponse = ""
                          + "HTTP/1.1 200 OK\r\n"
                          + "Content-Type: text/plain\r\n"
                          + "Content-Length: " + responseBody.length() + "\r\n"
                          + "Connection: close\r\n"
                          + "\r\n";
                  break;
                  
               default:
                  System.err.println("Método HTTP no soportado: " + method);
                  
                  // enviar una respuesta de error 501 Not Implemented
                  httpResponse = ""
                          + "HTTP/1.1 501 Not Implemented\r\n"
                          + "Content-Type: text/plain\r\n"
                          + "Content-Length: 0\r\n"
                          + "Connection: close\r\n"
                          + "\r\n";
                  
                  clientChannel.close();
                  return;
            }
            
            ByteBuffer outBuffer = ByteBuffer.wrap(httpResponse.getBytes(StandardCharsets.UTF_8));
            clientChannel.write(outBuffer);
            
            clientChannel.close();
            
         } catch (IOException e) {
            e.printStackTrace();
            try {
               clientChannel.close();
            } catch (IOException ex) {
               ex.printStackTrace();
            }
         }
      }
   }
   
   
   public static String getHandler(String resource, SocketChannel client ) throws IOException {
      String response = "";
      String bodyResponse = "";
      
      // Si la petición contiene parámetros
      if (resource.contains("?")) {
         System.out.println("Petición con parámetros");
         resource = resource.substring(resource.indexOf("?") + 1);   // Eliminar el recurso de la petición y obtener solo los parámetros
         
         // Obtener los parámetros de la petición
         Map<String, String> parameters = getParameters(resource);
         
         // Agregar los parámetros al cuerpo de la respuesta
         for (Map.Entry<String, String> entry : parameters.entrySet()) {
            //System.out.println("Parámetro: " + entry.getKey() + " Tiene: " + entry.getValue());
            bodyResponse += entry.getKey() + ": " + entry.getValue() + "\n";
         }
         
         // Crear la respuesta HTTP
         bodyResponse = deleteAcents(bodyResponse);
         response = createHead(200, "text/plain", bodyResponse.length());
         response += bodyResponse;
         return response;
      }
      
      // Si no hay parámetros, se envía el archivo solicitado o el index.html
      if (resource.equals("/") || resource.equals("/index.html") || resource.equals("/index.htm") || resource == null) {
         // Enviar el archivo index.html
         sendFile(client, "index.html");
         
      } else {
         resource = resource.substring(1); // Eliminar la barra inicial
         System.out.println("Recurso solicitado: " + resource);
         File file = new File(resource);
         
         // si el archivo existe y el ultimo caracter del recurso es No es un slash entonces se envia el archivo
         if (file.exists() && file.isFile() && resource.charAt(resource.length() - 1) != '/') {
            // Enviar el archivo
            sendFile(client, resource);
            
         } else if (file.exists() && file.isDirectory() && resource.charAt(resource.length() - 1) == '/') { // Si el recurso es un directorio y termina en /
            // Obtener la lista de archivos del directorio
            String[] fileNames = file.list();
            
            bodyResponse += "Archivos en el directorio:\n";
            for (String fileName : fileNames) {
               bodyResponse += fileName + "\n";
            }
            
            bodyResponse = deleteAcents(bodyResponse);
            response = createHead(200, "text/plain", bodyResponse.length());
            response += bodyResponse;
            
         } else if (file.exists() && file.isDirectory() && resource.charAt(resource.length() - 1) != '/') { // Si el recurso es un directorio y no termina en /
            // Simulación de redireccionamiento
            response = createHeadRedirect(301, "text/plain", 0, resource);
         } else {
            System.out.println("Archivo no encontrado: " + file.getName());
            bodyResponse = "Archivo o recurso no encontrado";
            response = createHead(404, "text/plain", bodyResponse.length());
            response += bodyResponse;
         }
      }
      
      //System.out.println("Respuesta:" + response);
      return response;
   }
   
   
   
   
   
   // Metodo para eliminar acentos y caracteres especiales de una cadena de texto. Util para evitar problemas con el envio de respuestas HTTP
   public static String deleteAcents(String text) {
      return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("[^\\p{ASCII}]", "");
   }
   
   // Metodo para crear una respuesta HTTP (cabecera)
   public static String createHead(int statusCode, String mimeType, long fileSize) {
      return "HTTP/1.1 " + statusCode + " " + HTTP_STATUS_CODES.get(statusCode) + "\r\n"
              + "Server: Hervert Server/1.0\r\n"
              + "Date: " + new Date() + "\r\n"
              + "Content-Type: " + mimeType + "\r\n"
              + "Content-Length: " + fileSize + "\r\n"
              + "Connection: close\r\n"
              + "\r\n";
   }
   
   // Metodo para crear una respuesta HTTP (cabecera) para redireccionamiento
   public static String createHeadRedirect(int statusCode, String mimeType, long fileSize, String location) {
      return "HTTP/1.1 " + statusCode + " " + HTTP_STATUS_CODES.get(statusCode) + "\r\n"
              + "Server: Hervert Server/1.0\r\n"
              + "Date: " + new Date() + "\r\n"
              + "Content-Type: " + mimeType + "\r\n"
              + "Content-Length: " + fileSize + "\r\n"
              + "Location: " + location + "/\r\n"
              + "Connection: close\r\n"
              + "\r\n";
   }
   
   
   // Metodo para obtener parametros de una petición. Recibe una cadena de formato "nombre=valor&nombre2=valor2"
   public static Map<String, String> getParameters(String parameters) {
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
   
   // Metodo para enviar un archivo al cliente (GET)
   private static void sendFile(SocketChannel clientChannel, String fileToSend) throws IOException {
      File file = new File(fileToSend);
      if (!file.exists() || !file.isFile()) {
         // Manejar el caso de archivo no encontrado
         String notFound = createHead(404, "text/plain", 0) + "Archivo no encontrado";
         ByteBuffer notFoundBuffer = ByteBuffer.wrap(notFound.getBytes(StandardCharsets.UTF_8));
         while (notFoundBuffer.hasRemaining()) {
            clientChannel.write(notFoundBuffer);
         }
         return;
      }
      
      // Determina la extensión y el MIME type usando tu tabla MIME_TYPES
      String mimeType = "application/octet-stream";
      int dotIndex = fileToSend.lastIndexOf(".");
      if (dotIndex != -1) {
         String extension = fileToSend.substring(dotIndex + 1).toLowerCase();
         mimeType = MIME_TYPES.getOrDefault(extension, "application/octet-stream");
      }
      
      // Prepara la cabecera HTTP
      String header = createHead(200, mimeType, file.length());
      
      // Envía la cabecera
      ByteBuffer headerBuffer = ByteBuffer.wrap(header.getBytes(StandardCharsets.UTF_8));
      while (headerBuffer.hasRemaining()) {
         clientChannel.write(headerBuffer);
      }
      
      // Envía el archivo en bloques
      try (FileChannel fileChannel = new FileInputStream(file).getChannel()) {
         ByteBuffer fileBuffer = ByteBuffer.allocate(8192);
         int bytesRead;
         while ((bytesRead = fileChannel.read(fileBuffer)) != -1) {
            fileBuffer.flip();
            while (fileBuffer.hasRemaining()) {
               clientChannel.write(fileBuffer);
            }
            fileBuffer.clear();
         }
      }
   }
}
