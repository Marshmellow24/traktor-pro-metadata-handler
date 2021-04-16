package clientServerTest;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ConnectionHandler {
    private int PORT;
    private int queueSize = 10;
    private ServerSocket serverSocket;
//    private volatile ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<String>();
    private volatile LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<String>(this.queueSize);
    private volatile ArrayList<ClientThread> threads = new ArrayList<ClientThread>();
    private volatile HashMap<ClientThread, UUID> threadMap = new HashMap<ClientThread, UUID>();
    
    public ConnectionHandler(int port) {
        this.PORT = port;
    }
    
    public ConnectionHandler(int port, int queueSize) {
        this.PORT = port;
        this.queueSize = queueSize;
    }
    
    public void start() throws IOException {
        this.serverSocket = new ServerSocket(this.PORT);
        System.out.println("[CONNECTION HANDLER] Waiting for clients to connect...");
        while (true) {
            ClientThread client = new ClientThread(serverSocket.accept(), this.queue, UUID.randomUUID());
            client.start();
            this.threads.add(client);
            this.threadMap.put(client, client.getUUID());
            
        }
    }
    
    public String getThreadList() {
        StringBuilder threadList = new StringBuilder();
      
        for (ClientThread t : this.threads) {
            threadList.append("Thread: ");
            threadList.append(t.clientType);
            threadList.append(" with id: ");
            threadList.append(t.getUUID().toString());            
        }
        
        return threadList.toString();
    }
    
    private class ClientThread extends Thread {
        private Socket clientSocket;
        private BufferedReader in;
        private BufferedWriter out;
        private LinkedBlockingQueue<String> queue;
        private ArrayList<String> pullQueue; 
        private UUID id;
        
        private String clientType;
        private boolean pullData; 
        
        public ClientThread(Socket clientSocket, LinkedBlockingQueue<String> queue, UUID id) {
            this.clientSocket = clientSocket;
            this.queue = queue;
            this.id = id;
        }
        
        public UUID getUUID() {
            return this.id;
        }
        
        public String getClientType() {
            return this.clientType;
        }
        
        public void terminate() {
            this.pullData = false;
        }
        
        public void run() {
            try {
                this.in = new BufferedReader(new InputStreamReader(this.clientSocket.getInputStream()));
                this.out = new BufferedWriter(new OutputStreamWriter(this.clientSocket.getOutputStream()));
            } catch (IOException e) {
                e.printStackTrace();
            }
            
            String clientCode = null;
            while (this.clientType == null) {
                try {
                    clientCode = this.in.readLine();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                
                switch (clientCode) {
                    // Client wants to send data to connection handler, i.e. it is a MetaDataCollector
                    case "CODE001SENDER": 
                        this.clientType = "SENDER";
                        
                        String confirmation = "[CONNECTION HANDLER] " + this.getClientType()
                        + " client connected to handler with id " + this.getUUID().toString()
                        + ". Handler is now ready to receive meta data.";
                        System.out.println(confirmation);
                       
                        try {
                            this.out.write(confirmation);
                            this.out.newLine();
                            this.out.flush();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        // Ab hier wird meta daten stream empfangen
                        String input;
                        StringBuilder tagMerger = new StringBuilder();

                        try {
                            while((input = this.in.readLine()) != null) {
                                if(this.queue.size() == 10) {
                                    this.queue.remove();
                                }
                                
                                tagMerger.append(input + " ");
                                
                                if (input.contains("TITLE")) {
                                    tagMerger.deleteCharAt(tagMerger.length() - 1);
                                    this.queue.put(tagMerger.toString());
                                    System.out.println(this.queue.toString());
                                    tagMerger.delete(0, tagMerger.length());
                                }
                                
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        
                        
                        break;
                        
                    // Client wants to receive data from connection handler, i.e. it is an app user                    
                    case "CODE002RECEIVER":
                        this.clientType = "RECEIVER";
                        
                        confirmation = "[CONNECTION HANDLER] " + this.getClientType()
                        + " client connected to handler with id " 
                        + this.getUUID().toString() + ". Handler is now ready to send meta data to app user.";
                        System.out.println(confirmation);
                                                
                        try {
                            this.out.write(confirmation);
                            this.out.newLine();
                            this.out.flush();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        
                        this.pullData = true;
                        
                        while (pullData) {
                            if(!this.queue.isEmpty()) {
                                try {
                                    this.pullQueue = new ArrayList<String>(this.queue);
                                    for (String m : this.pullQueue) {
                                        this.out.write(m);
                                        this.out.newLine();
                                        this.out.flush();
                                    }
                                    Thread.sleep(2000);
                                    
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                    
                                } catch (SocketException e) {
                                	System.out.println("[CONNECTION HANDLER] Lost connection to client with id " + this.getUUID().toString());
                                	this.terminate();
                                	
                                } catch (IOException e) {
                                	e.printStackTrace();
                                }
                                
                                
                        }
                            
//                        try {
//                            
//                        } catch (Exception e) {
//                            e.printStackTrace();
//                        }
                    }
                } 
            }
        }
    }
    
	public static void main(String[] args) throws IOException {
            ConnectionHandler connector = new ConnectionHandler(8000);
            connector.start();
	}
        

        
}
        
       
