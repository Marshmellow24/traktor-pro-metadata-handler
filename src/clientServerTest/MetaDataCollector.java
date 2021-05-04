package clientServerTest;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;

import com.sun.jmx.snmp.Timestamp;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;


/*
 * TODO
 * - Falls Buffer nur TITLE oder ARTIST hat sichergehen, dass beides trotzdem ausgegegeben wird 
 *   (passiert wenn man bei traktor hintereinander lieder vom selben interpreten streamt)|| DONE (Fehler kommt wenn stream seitens server unterbrochen, wiederaufgenommen wird und derselbe artist gespielt wird)
 *   -> Neustart von Traktor oder Wechsel von Interpreten behebt das Problem ad-hoc
 * - Bei Zeilenumbruch zwischen TITLE und ARTIST darf Metainfo nicht abgehackt sein (siehe MOSH, oder Pharcyde - passiert wenn direkt nach artist namen zeilenumbruch kommt - es fehlt ein leerzeichen) || DONE
 * - RegEx verwenden f�r parsen von Metadaten (verhindert dann Punkt 2) und vllt noch das parsen von songdaten die ausschlie�lich in TITLE gespeichert sind. die aufsplitten und richtig zuordnen.
 * - Exception handling wenn verbindung seitens traktor unterbrochen wird || DONE
 * - debug control mit boolean einrichten || DONE
 */

public class MetaDataCollector {
	private final int PORT;
	private boolean DEBUG = false;
    private int queueSize = 10;
	
	private boolean RUN = false;
	
	private ServerSocket listener;
	private Socket client;
	private BufferedReader receive;
	private BufferedWriter send;
        
	private Socket senderClient;

    private volatile StringBuilder results = new StringBuilder();
    private volatile ArrayList<String> resultList = new ArrayList<String>();
    private volatile LinkedBlockingQueue<String> outQueue = new LinkedBlockingQueue<String>(this.queueSize);
	
	
	/**
	 * Constructor w/o Debugging param
	 * 
	 * @param port		sets the port Traktor Pro is going to connect to
	 */
	
	public MetaDataCollector(int port) {
            this.PORT = port;
	}
	
	
	/**
	 * Constructor w/ Debugging param
	 * 
	 * @param port		sets the port Traktor Pro is going to connect to
	 * @param queueSize sets the queue size of the transmitted list which contains the meta data
	 * @param debug		set 'true' if debug mode should be enabled (Default is 'false')	 
	 */
	
	public MetaDataCollector(int port, int queueSize, boolean debug ) {
            this.PORT = port;
            this.queueSize = queueSize;
            this.DEBUG = debug;
	}
        
        
	/** 
	 * Sets up input and output streams for this instance
	 * 
	 * @throws IOException
	 * 
	 */
	
	private void setUpStreams() throws IOException {
            this.send = new BufferedWriter(new OutputStreamWriter(this.client.getOutputStream()));
            this.receive = new BufferedReader(new InputStreamReader(this.client.getInputStream()));
            System.out.println("[DEBUG]Input/OutputStreams ge�ffnet");
	}
	
	
	/** 
	 * Closes input and output streams for this instance
	 * 
	 * @throws IOException
	 * 
	 */
	
	private void closeStreams() throws IOException {
            this.send.close();
            this.receive.close();
            this.client.close();
            this.listener.close();
            System.out.println("[DEBUG]Input/OutputStreams sowie Sockets geschlossen");
	}
	
	
	/** 
	 * Sends the HTTP Response to Traktor so that Vorbis stream is initiated. (Required by Traktor Pro)
	 * 
	 * @throws IOException
	 * 
	 */
	
	private void sendHTTPResponse() throws IOException {
            this.send.write("HTTP/1.0 200 OK");
            this.send.newLine(); // muss nach jeder Zeile gesendet werden (sonst kann scheinbar readline() nicht funktionieren)
            this.send.write("\r\n");
            this.send.newLine();
            this.send.flush(); // Sendet erst die Nachricht 
            System.out.println("[DEBUG]HTTP Response gesendet");
	}
	
	
	/** 
	 * Receives the stream Header sent by Traktor, which contains connection Type data and more.
	 * 
	 * @throws IOException
	 * 
	 */

	private String receiveHeaderInfo() throws IOException {
            StringBuilder streamHeader = new StringBuilder();
		
            for (int i = 0; i < 16; i++) {
                // Nur um streaminfos zu empfangen (16 Zeilen Stream Header)
                String buffer = this.receive.readLine();
                streamHeader.append(buffer);
                streamHeader.append("; ");
                //System.out.println(streamHeader);
            }
            streamHeader.append("\n");
            System.out.println("[DEBUG] HeaderInfo erhalten");
            return streamHeader.toString();
	}
	
	
	/**
	 * Method which passes through the StringBuilder and handles the possibility that a new read line doesn't contain both tags, 
	 * i.e. has to be able to merge two lines into the StringBuilder.
	 * 
	 * @param outputReadLine	Line that got read from inputStream
	 * @param lineBuffer		StringBuilder that contains either Line with ARTIST Tag and new Line will be appended to it, or nothing (yet) 
	 * @return					the StringBuilder which contains at least one Tag
	 */
	
	private StringBuilder handleLineBreakInData(String outputReadLine, StringBuilder lineBuffer) {
		
            if (outputReadLine.contains("TITLE") || outputReadLine.contains("ARTIST")) {
                    /* Falls output von BufferedReader TITLE und/oder ARTIST enth�lt in StringBuilder output_buffer laden -> nicht jede Zeile wird geparsed von parseBuffer
                     * Falls nur ARTIST enthalten, gibt parsebuffer nichts aus da TITLE vom selben song noch in n�chster Zeile folgt und an outputbuffer angehangen wird im n�chsten schleifendurchlauf.
                     * -> erm�glicht, dass bei einem Zeilenumbruch zwischen ARTIST u. TITLE beide Zeilen aneinandergef�gt werden 
                     */		 
                    if (lineBuffer.length() != 0) { 
                            // output_buffer hat eine schleife "�berstanden" ohne gel�scht zu werden (parseBuffer hat leeren String ausgegeben)
                            lineBuffer.append(" "); 
                            // Manchmal fehlt ein Leerzeichen und so passt die IndexOf Berechnung von parseBuffer nicht mehr und beschneidet den ARTIST Tag 
                            lineBuffer.append(outputReadLine);
                    } else {
                            // Falls outputbuffer leer ist, einfach normal String einf�gen 
                            lineBuffer.append(outputReadLine);
                    }
            }
            return lineBuffer;
	}
	
	
	/**
	 * Adds MetaTags of song to StringBuilder of this instance so that get MetaTag list can be extended. 
	 * Also returns the tags + time as String.
	 * 
	 * 
	 * @param result		String that contains both Tags (has been parsed by parseBuffer already)
	 * @return				the tags + current time as String
	 */
	
	private String handleResult (String result) throws InterruptedException {	
            String metaTags;
            Timestamp timestamp = new Timestamp(System.currentTimeMillis());
		
            metaTags = "\n[" + timestamp.getDate() + "]\n" + result;
		
            //this.resultList.append(metaTags + System.lineSeparator());
            this.outQueue.put(result + System.lineSeparator());
            return metaTags;
	}
	
	
	/**
	 * Emits the resultList as a String.
	 * 
	 * @return		resultList of this instance as String.
	 */
	
	public String getResultList() {
            
            for(String r : this.resultList) {
                this.results.append(r);
                this.results.append(System.lineSeparator());
            }
            return this.results.toString();
	}
                
        
	/** 
	 * Reads the String out of the inputStream and handles it accordingly. Runs until stream is empty.
	 * 
	 * @throws IOException
	 * 
	 */
	
	private void startCollecting() throws IOException, InterruptedException {
		String outputReadLine;
		StringBuilder lineBuffer = new StringBuilder();
		
		while ((outputReadLine = this.receive.readLine()) != null) {				
			String result = "";
			
			lineBuffer = this.handleLineBreakInData(outputReadLine, lineBuffer);
			
			if (this.DEBUG) {
				// Falls debugmodus an - bufferedReader Inhalt ausgeben
				System.out.println("[OUTPUT]");
				System.out.println(outputReadLine);
				
				if (lineBuffer.length() != 0) {
					// Falls output von BufferedReader TITLE oder ARTIST enth�lt, Inhalt der in output_buffer geladen wurde ausgeben -> Man sieht nur die Zeilen die 						ARTIST o. TITLE enthalten
					System.out.println("[OUTPUT BUFFER]");
					System.out.println(lineBuffer.toString());
				}
				
				result = this.parseBuffer(lineBuffer.toString()); // parsebuffer exception nicht handlen, um Zeile der Fehlerquelle zu erhalten
				
			} else {
				// falls debug=false, bufferinhalt parsen und exception handling betreiben
				try {
					result = this.parseBuffer(lineBuffer.toString());
				} catch (StringIndexOutOfBoundsException e) {
					System.out.println(e.getMessage()); 
				}	
			}
			
			if (result.length() != 0) {
				// ist result != 0 hat parseBuffer etwas ausgegeben (aka findet TITLE und ARTIST oder nur TITLE, falls Traktor bugged)
				System.out.println(this.handleResult(result));
				lineBuffer.delete(0, lineBuffer.length());
				// output buffer, der Zeilen mit TITLE und ARTIST enth�lt clearen, damit sp�tere Metatags kein IndexOutOfboundsExceptions fangen
				result = null;
				// result l�schen -> unn�tig wahrscheinlich, weil sowieso bei jedem parseBuffer neu bef�llt wird
			}
		}
	}
	
	
	/** 
	 * Stops server (detailed: breaks while loop in runserver which initiates closestreams() method).
	 * 
	 */
	
	public void stopReceiver() {
		this.RUN = false;
		System.out.println("[DEBUG] Server gestoppt.");
	}
	
	
	/** 
	 * Sets up a server socket and listens to the given port. As soon as Traktor Pro broadcasts to the according port, connection is established.
	 * After sending an HTTP OK response through the OutputStream back to Traktor Pro, the streaming begins and the collector starts receiving the OGG Vorbis stream.
	 *
	 * @throws IOException
	 * 
	 */
	
	public void runReceiver() throws IOException, InterruptedException {
	    
            this.RUN = true;
            this.listener = new ServerSocket(this.PORT);
		
            System.out.println("[SERVER] Server waiting for client to connect on port: " + this.listener.getLocalPort());
            
            while (this.RUN) {
                //String output;
                this.client = listener.accept();
                this.setUpStreams();
                
                System.out.println("\n[SERVER] Client connected through: " + this.client.getRemoteSocketAddress());
                System.out.println("[SERVER] Sending HTTP Response...\n");
                this.sendHTTPResponse();
                System.out.println("[SERVER] Streaming begins\n");
                
                
                SenderThread senderThread = new SenderThread(this, "127.0.0.1", 8000);
                senderThread.start();

                System.out.println(this.receiveHeaderInfo());	
                
                try {
                    this.startCollecting();

                } catch (SocketException e) {
                    System.out.println("\n[SERVER] Connection to client lost. Press Broadcast Button in Traktor to reconnect.");
                    System.out.println(this.getResultList());
                }

            }
            
		
            System.out.println("[SERVER] Listening on port has been stopped, invoke startCollecting() to continue.");
            
            this.closeStreams();	
	}
	
    public void runSender(String ip, int port) throws IOException, ConnectException {
        this.senderClient = new Socket(ip, port);
        System.out.println("[DEBUG] MDC ready to send parsed MetaData to Handler");
        BufferedWriter sendToHandler = new BufferedWriter(new OutputStreamWriter(this.senderClient.getOutputStream()));
            
        sendToHandler.write("CODE001SENDER");
        sendToHandler.newLine();
        sendToHandler.flush();
        
        while (this.RUN) {

            
            if (!this.outQueue.isEmpty()) {
                sendToHandler.write(this.outQueue.poll());
                sendToHandler.flush();
            }
            
        }
    }
	
	/**
	 * Method that parses line for metatags. For now only viable for Traktor PRO ogg vorbis encoded streams.
	 * 
	 * @param buffer	line that contains either ARTIST or TITLE tag (or both)
	 * @return			parsed metatags in case that line contains ARTIST and TITLE or only TITLE. If it only contains ARTIST this method returns an empty string 
	 * @throws StringIndexOutOfBoundsException
	 */
	
	public String parseBuffer(String buffer) throws StringIndexOutOfBoundsException {
		final String ARTIST = "ARTIST=";
		final String TITLE = "TITLE=";
		final String START = "ENCODER"; // Delimiter um Anzahl der "vorbis" zu verringern und IndexOutOfBounds zu verhindern (zwischen start und end tag kommt nur ein vorbis vor)
		final String END = "BCV"; // Delimiter
		 
		StringBuilder metadata = new StringBuilder();
		String cache;
		 
		if ((buffer.contains(TITLE) && buffer.contains(ARTIST))) {
			// Zeile enth�lt beide tags und kann geparsed werden
			if (buffer.contains(START) && buffer.contains(END)) {
				/* falls zeile start und end (s.o.) enth�lt wird hier der inhalt zwischen beiden tags in cache geladen und dann die metatags geparsed 
				 * -> Bsp: ENCODER=zzz ARTIST=xxx TITLE= yyyy BCV 
				 */
				cache = buffer.substring((buffer.indexOf(START)), buffer.indexOf(END)); // cut out comment of vorbis container (artist and title are contained)
				metadata.append(cache.substring(cache.indexOf(ARTIST), (cache.lastIndexOf(TITLE) - 4))); // Artist - search for "artist=" and subtract that from "title=" index
				metadata.append(System.lineSeparator());
				metadata.append(cache.substring(cache.indexOf(TITLE), (cache.lastIndexOf("vorbis") - 2))); // Title - search for "title=" and subtract that from "vorbis=" index
			} else if (buffer.contains(TITLE) && buffer.contains(ARTIST)) { 
				/* Falls eine zeile nach start tag beginnt k�nnen tags trotzdem geparsed werden.
				 * -> Bsp: ARTIST=xxx TITLE= yyyy BCV
				 *  Aber NUR wenn beide vorhanden (verhindert dass Tags nicht zusammengef�hrt werden wenn Zeilenumbruch zwischen ARTIST und TITLE vorkommt)
				 */
				metadata.append(buffer.substring(buffer.indexOf(ARTIST), (buffer.indexOf(TITLE) - 4)));
				metadata.append(System.lineSeparator());
				metadata.append(buffer.substring(buffer.indexOf(TITLE), buffer.indexOf("vorbis") - 2));
			} 
			 
		} else if (buffer.contains(TITLE) /*&& !buffer.contains(ARTIST)*/) {
			/* Traktor �bertr�gt teilweise nur noch den TITLE falls der ARTIST zum letztgestreamten song gleichgeblieben ist. 
			 * Was wenn dann nur TITLE geparsed wird obwohl ARTIST vorhanden aber in anderer Zeile? Da Aufbau von Metatags immergleich (1.encoder 2.artist 3.title) kann das nicht passieren.
			 * ARTIST tag w�re davor erschienen und noch im metabuffer gewesen.
			 * Trotzdem Kontrolle, dass nur TITLE tag enthalten ist und NICHT ARTIST tag 
			 */
			metadata.append(buffer.substring(buffer.indexOf(TITLE), buffer.indexOf("BCV") - 9)); 
			// END Tag muss BCV sein weil es zu viele vorbis in einer zeile geben kann (lastindex vorbis w�re zu weit weg vom tag) und START tag hier nicht vorkommt 
			System.out.println("\n[SERVER] Please consider restarting Traktor or changing the Song to a different interpret. It doesn't send the ARTIST tag anymore.");
		} 
		// Falls buffer Zeile nur Artist enth�lt gibt parseBuffer einen leeren String aus, outputbuffer wird nicht gel�scht und die zeile wird im n�chsten schleifen durchlauf erweitert um die 		Zeile mit dem n�chsten treffer
			return metadata.toString(); 
		}
        
    public class SenderThread extends Thread {
        private MetaDataCollector mdc;
        private String ip;
        private int port;
        
        public SenderThread(MetaDataCollector mdc, String ip, int port) {
            this.mdc = mdc;
            this.ip = ip;
            this.port = port;
        }

        public void run() {
            try {
                this.mdc.runSender(this.ip, this.port);  
            } catch (Exception ex) {
                ex.printStackTrace();
            } 
        }
    }
      
	public static void main(String[] args) throws IOException, InterruptedException {
		MetaDataCollector meta = new MetaDataCollector(9090);
		meta.runReceiver();	
	}
	 
	
 
}
