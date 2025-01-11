import com.google.gson.JsonParser;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
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
            int totalBytesRead = 0;
            while ((bytesRead = clientChannel.read(bufferByte)) > 0) {
               bufferByte.flip();
               bufferBAOS.write(bufferByte.array(), 0, bytesRead);
               bufferByte.clear();
               totalBytesRead += bytesRead;
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
            
            ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();
            bodyBuffer.write(bufferBAOS.toByteArray(), bufferBAOS.toString().indexOf("\r\n\r\n") + 4, totalBytesRead - bufferBAOS.toString().indexOf("\r\n\r\n") - 4);
            
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
                  httpResponse = postHandler(request, bodyBuffer, resource);
                  
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
   
   // La petición HTTP POST se utiliza para enviar datos al servidor para que procese una acción específica. Ej:
   // Enviar datos de un formulario HTML al servidor, agregar un nuevo registro a una base de datos, realizar un pago, autenticar a un usuario, etc.
   public static String postHandler(String request, ByteArrayOutputStream bodyBuffer, String resource) {
      String[] requestParts = request.split("\r\n");
      int contentLength = 0;
      String bodyRequest = "";
      String contentType = "";
      String response = "";
      String boundary = "";   // Si la petición es de tipo multipart/form-data
      Map<String, String> parameters;
      
      // Buscar la cabecera Content-Length en la petición HTTP
      for (String part : requestParts) {
         if (part.contains("Content-Length")) contentLength = Integer.parseInt(part.split(":" )[1].trim());
         if (part.contains("Content-Type")) contentType = part.split(":")[1].trim();
      }
      
      // Si la petición es de tipo multipart/form-data, buscar el boundary
      if (contentType.contains("multipart/form-data")) {
         boundary = contentType.split("boundary=")[1];
         contentType = "multipart/form-data";
      }
      
      System.out.println("Content-Length: " + contentLength);
      System.out.println("Content-Type: " + contentType);
      
      // Si la petición tiene cuerpo crear una respuesta según el tipo de contenido
      if (contentLength > 0) {
         switch (contentType) {
            case "application/x-www-form-urlencoded":
               // Extraer los parámetros del cuerpo de la petición y agregarlos al cuerpo de la respuesta
               bodyRequest = URLDecoder.decode(request.substring(request.lastIndexOf("\r\n\r\n") + 4), StandardCharsets.UTF_8);
               parameters = getParameters(bodyRequest);
               
               bodyRequest = "";
               for (Map.Entry<String, String> entry : parameters.entrySet()) {
                  bodyRequest += entry.getKey() + ": " + entry.getValue() + "\n";
               }
               
               if (!resource.equals("/")) {
                  int statusUpdateForm = updateFormSimulation(resource.substring(1), parameters);
                  if (statusUpdateForm == 200) {
                     bodyRequest = "Formulario actualizado";
                     response = createHead(200, "text/plain", bodyRequest.length());
                     response += bodyRequest;
                  } else {
                     bodyRequest = "Formulario no encontrado";
                     response = createHead(404, "text/plain", bodyRequest.length());
                     response += bodyRequest;
                  }
                  break;
               } else {
                  bodyRequest = deleteAcents(bodyRequest);
                  response = createHead(200, "text/plain", bodyRequest.length());
                  response += bodyRequest;
                  break;
               }
            
            case "multipart/form-data":
               // Construir una cadena en formato nombre=valor&nombre2=valor2
               String[] parts = request.split("--" + boundary);
               
               for (String part : parts) {
                  if (part.contains("Content-Disposition")) {
                     String[] disposition = part.split("\r\n");
                     String name = disposition[1].split("name=\"")[1].split("\"")[0];
                     String value = part.substring(part.indexOf("\r\n\r\n") + 4, part.lastIndexOf("\r\n"));
                     bodyRequest += name + "=" + value + "&";
                  }
               }
               bodyRequest = bodyRequest.substring(0, bodyRequest.length() - 1); // Eliminar el último &
               
               parameters = getParameters(bodyRequest);
               
               bodyRequest = "";
               for (Map.Entry<String, String> entry : parameters.entrySet()) {
                  bodyRequest += entry.getKey() + ": " + entry.getValue() + "\n";
               }
               
               if (!resource.equals("/")) {
                  int statusUpdateForm = updateFormSimulation(resource.substring(1), parameters);  // Eliminar la barra inicial
                  if (statusUpdateForm == 200) {
                     bodyRequest = "Formulario actualizado";
                     response = createHead(200, "text/plain", bodyRequest.length());
                     response += bodyRequest;
                  } else {
                     bodyRequest = "Formulario no encontrado";
                     response = createHead(404, "text/plain", bodyRequest.length());
                     response += bodyRequest;
                  }
                  break;
               } else {
                  bodyRequest = deleteAcents(bodyRequest);
                  response = createHead(200, "text/plain", bodyRequest.length());
                  response += bodyRequest;
                  break;
               }
            
            
            case "application/json":
               bodyRequest = URLDecoder.decode(request.substring(request.lastIndexOf("\r\n\r\n") + 4), StandardCharsets.UTF_8);
               if (isValidJson(bodyRequest)) {
                  bodyRequest = deleteAcents(bodyRequest);
                  response = createHead(200, "application/json", bodyRequest.length());
                  response += bodyRequest;
                  
                  // Guardar el archivo JSON en el servidor
                  //saveFile("archivo" + System.currentTimeMillis() + ".json", bodyRequest.getBytes());
                  
               } else {
                  bodyRequest = "JSON mal formado";
                  response = createHead(400, "text/plain", bodyRequest.length());
                  response += bodyRequest;
               }
               break;
            
            case "application/xml":
               bodyRequest = URLDecoder.decode(request.substring(request.lastIndexOf("\r\n\r\n") + 4), StandardCharsets.UTF_8);
               if (isValidXml(bodyRequest)) {
                  bodyRequest = deleteAcents(bodyRequest);
                  response = createHead(200, "application/xml", bodyRequest.length());
                  response += bodyRequest;
               } else {
                  bodyRequest = "XML mal formado";
                  response = createHead(400, "text/plain", bodyRequest.length());
                  response += bodyRequest;
               }
               break;
            
            case "text/html":
               bodyRequest = URLDecoder.decode(request.substring(request.lastIndexOf("\r\n\r\n") + 4), StandardCharsets.UTF_8);
               if (isValidHtml(bodyRequest)) {
                  bodyRequest = deleteAcents(bodyRequest);
                  response = createHead(200, "text/html", bodyRequest.length());
                  response += bodyRequest;
               } else {
                  bodyRequest = "HTML mal formado";
                  response = createHead(400, "text/plain", bodyRequest.length());
                  response += bodyRequest;
               }
               break;
            
            case "text/plain":
               if (!resource.equals("/")) {
                  int statusUpdateFile = updateFileText(resource.substring(1), URLDecoder.decode(request.substring(request.lastIndexOf("\r\n\r\n") + 4), StandardCharsets.UTF_8), false);
                  if (statusUpdateFile == 200) {
                     bodyRequest = "Archivo actualizado";
                     response = createHead(200, "text/plain", bodyRequest.length());
                     response += bodyRequest;
                  } else {
                     bodyRequest = "Archivo no encontrado";
                     response = createHead(404, "text/plain", bodyRequest.length());
                     response += bodyRequest;
                  }
                  break;
               } else {
                  bodyRequest = URLDecoder.decode(request.substring(request.lastIndexOf("\r\n\r\n") + 4), StandardCharsets.UTF_8);
                  bodyRequest = deleteAcents(bodyRequest);
                  response = createHead(200, "text/plain", bodyRequest.length());
                  response += bodyRequest;
                  break;
               }
            
            
            default:
               bodyRequest = "Como el tipo de contenido no es soportado, se almacenara en el servidor";
               saveFile("archivo_" + System.currentTimeMillis() + "." + getKeyByValue(MIME_TYPES, contentType), bodyBuffer.toByteArray());
               response = createHead(200, "text/plain", bodyRequest.length());
               response += bodyRequest;
               break;
         }
      } else {
         bodyRequest = "Peticion POST sin cuerpo";
         response = createHead(400, "text/plain", bodyRequest.length());
         response += bodyRequest;
      }
      
      return response;
   }
   
   public static int updateFormSimulation(String form, Map<String, String> parameters) {
      // Abrir el archivo form.txt y gaurdar su contenido en una cadena
      String formFileName = form;
      if (!formFileName.endsWith(".txt")) {
         formFileName += ".txt";
      }
      
      File file = new File(formFileName);
      
      if (file.exists()) {
         // Leer el contenido del archivo
         String formContent = "";
         Map<String, String> formParameters = new HashMap<>();
         
         try (BufferedReader reader = new BufferedReader(new FileReader(formFileName))) {
            String line;
            while ((line = reader.readLine()) != null) {
               formContent += line + "\n";
               String[] keyValue = line.split(":");
               
               if (keyValue.length == 2) {
                  formParameters.put(keyValue[0].trim(), keyValue[1].trim());
               } else {
                  formParameters.put(keyValue[0], "");
               }
            }
         } catch (IOException e) {
            e.printStackTrace();
         }
         
         System.out.println("\nParámetros del formulario existente:");
         for (Map.Entry<String, String> entry : formParameters.entrySet()) {
            System.out.println(entry.getKey() + ": " + entry.getValue());
         }
         
         System.out.println("\nParámetros del formulario entrante:");
         for (Map.Entry<String, String> entry : parameters.entrySet()) {
            System.out.println(entry.getKey() + ": " + entry.getValue());
         }
         
         // Actualizar los parámetros del formulario existente con los nuevos parámetros
         for (Map.Entry<String, String> entry : parameters.entrySet()) {
            formParameters.put(entry.getKey(), entry.getValue());
         }
         
         System.out.println("\nParámetros del formulario actualizado:");
         for (Map.Entry<String, String> entry : formParameters.entrySet()) {
            System.out.println(entry.getKey() + ": " + entry.getValue());
         }
         
         // Guardar los parámetros actualizados en el archivo
         try (BufferedWriter writer = new BufferedWriter(new FileWriter(formFileName))) {
            for (Map.Entry<String, String> entry : formParameters.entrySet()) {
               writer.write(entry.getKey() + ": " + entry.getValue() + "\n");
            }
         } catch (IOException e) {
            e.printStackTrace();
         }
         return 200;
      } else {
         return 404;
      }
   }
   
   public static int updateFileText(String fileName, String text, boolean replace) {
      // Actualizar el contenido de un archivo solo si existe y es de texto
      File file = new File(fileName);
      
      if (file.exists() && file.isFile() && fileName.endsWith(".txt")) { // Mejor usar endsWith para mayor precisión
         try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, !replace))) {
            // Si replace es true, el archivo se abre en modo de sobrescritura.
            // Si replace es false, el archivo se abre en modo de append.
            writer.write(text);
         } catch (IOException e) {
            e.printStackTrace();
            return 500; // Código de error para problemas del servidor
         }
         return 200; // Código de éxito
      } else {
         return 404; // Código de error para archivo no encontrado
      }
   }
   
   // Metodo para obtener la clave de un valor en un mapa, util para extraer la extensión de un archivo a partir de su mime type
   public static String getKeyByValue(Map<String, String> map, String value) {
      for (Map.Entry<String, String> entry : map.entrySet()) {
         if (entry.getValue().equals(value)) {
            return entry.getKey();
         }
      }
      return "";  // Si no se encuentra el valor, se retorna una cadena vacía
   }
   
   public static boolean isValidJson(String json) {
      try {
         JsonParser.parseString(json);
         return true;
      } catch (Exception e) {
         return false;
      }
   }
   
   public static boolean isValidHtml(String html) {
      try {
         return html.contains("!DOCTYPE html") &&
                 html.contains("<html") &&
                 html.contains("<head>") &&
                 html.contains("<body>") &&
                 html.contains("</body>") &&
                 html.contains("</head>") &&
                 html.contains("</html>");
      } catch (Exception e) {
         return false;
      }
   }
   
   public static boolean isValidXml(String xml) {
      try {
         DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
         DocumentBuilder builder = factory.newDocumentBuilder();
         builder.parse(new ByteArrayInputStream(xml.getBytes()));
         return true;
      } catch (Exception e) {
         return false;
      }
   }
   
   public static void saveFile(String fileName, byte[] fileBytes) {
      try (FileOutputStream fileOutput = new FileOutputStream(fileName)) {
         fileOutput.write(fileBytes);
      } catch (IOException e) {
         e.printStackTrace();
      }
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
