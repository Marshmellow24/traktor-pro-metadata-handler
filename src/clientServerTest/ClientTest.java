package clientServerTest;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;


public class ClientTest {

	public static void main(String[] args) throws IOException {
		// TODO Auto-generated method stub
		Socket clientSocket = new Socket("127.0.0.1", 8000);
		
		
		BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
		BufferedWriter out = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));
                
        out.write("CODE002RECEIVER");
        out.newLine();
        out.flush();
                
		String resp = null; 
                
        while((resp = in.readLine()) != null) {
            System.out.println(resp);
        }
	
		System.out.println(resp);
	}

}