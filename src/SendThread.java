import java.io.*;
import java.net.*;
import java.util.*;

public class SendThread extends Object implements Runnable
{
	public static DatagramSocket sock;
	HashMap<Integer, Integer> list = new HashMap<Integer, Integer>();
	HashMap< Integer, ArrayList<Integer> > routingTable = new HashMap< Integer, ArrayList<Integer> >();
	public ArrayList<Integer> unstable = new ArrayList<Integer>();
	static InetAddress IP = null;
	public int nodePort;
	public boolean tracewait = false;
	String tableString = null;
	
	/*
	 * Constructor that creates/binds socket, creates the neighbour list, unstable (no ACK) node list, and port of itself
	 */
	public SendThread(DatagramSocket socket, HashMap<Integer, Integer> list, ArrayList<Integer> unstable, int nodePort) throws UnknownHostException
	{
		sock = socket;
		this.list = list;
		this.unstable = unstable;
		this.nodePort = nodePort;

		IP = InetAddress.getByName("localhost");
	}
	
	/*
	 * Creates and sends verification messages
	 */
	public void verify(int port, int cost) throws IOException
	{
		// Sends the message	
		String message = ".verify " + cost;
		byte[] sendData  = new byte[1024];		
		sendData = message.getBytes(); 	
		DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IP, port);
		sock.send(sendPacket);
		
		//System.out.println("Sending: " + message);
		System.out.println("Message sent from Node (Port " + nodePort + ") to Node (Port " + port + ")");
	}

	public void run() 
	{
		// Declare the sending variables
		String message = null;
		byte[] sendData  = new byte[1024];
		DatagramPacket sendPacket = null;

		// For each of the node's neighbours, send verification message
		Iterator<Integer> iterator = list.keySet().iterator(); 
		while (iterator.hasNext())
		{
			int port = iterator.next();
			int cost = list.get(port);
			
			// Sends verification messages only to the ones that have not ACKed
			if (unstable.contains((Object) port))
			try { verify (port, cost); } 
			catch (IOException e) {}		
		}
		
		// Wait for all nodes to ACK
		while (unstable.size() > 0)
		{
			try { verified(); } 
			catch (InterruptedException e) {}
		}
		
		// Upon completion of link verification, initiate flooding
		System.out.println("\nMessage flooding started.");
		message = ".startflood";
		sendData  = new byte[1024];		
		sendData = message.getBytes(); 
		sendPacket = new DatagramPacket(sendData, sendData.length, IP, nodePort);
		try { sock.send(sendPacket); } 
		catch (IOException e) {}

		// Waits 5 seconds, any message received in that time frame will reset the timer
		nodeWait(5000);
		
		// After 5 seconds, if not all nodes ACK'ed, then program will display error and terminate
		if (unstable.size() != 0)
		{
			System.out.println("Node (Port " + nodePort + ") cannot reach Node (Port " + unstable.get(0) + ")");
			System.out.println("Link failure occurred. Program terminated.");
			System.exit(0);
		}

		// Terminate flooding and initiate table construction
		message = ".endflood";
		sendData  = new byte[1024];		
		sendData = message.getBytes(); 
		sendPacket = new DatagramPacket(sendData, sendData.length, IP, nodePort);
		try { sock.send(sendPacket); } 
		catch (IOException e) {}	
		
		synchronized (this)
		{
			try {	wait();	} 
			catch (InterruptedException e) { }
		}
		
		try {	send();	} 
		catch (IOException e) {	} 
		catch (InterruptedException e) { 	}
	}
	
	/*
	 * Provides a timer for the verification process
	 */
	public void verified() throws InterruptedException
	{
		//While nodes haven't ACKed
		while ( unstable.size() > 0)
		{
			int s = unstable.size();
			
			//ACK time of 500msec
			synchronized(this) { Thread.sleep(500); } 
			
			//If unstable node list does not change, an element inside is unreachable
			if (unstable.size() == s)
			{
				System.out.println("Node (Port " + nodePort + ") cannot reach Node (Port " + unstable.get(0) + ")");
				System.out.println("Link failure occurred. Program terminated.");
				System.exit(0);
			}
		}
		
		System.out.println("Link status verification finished.\n");
		
		//Wait 1 second for other nodes to finish verification
		synchronized(this) { Thread.sleep(1000); }
	}
	
	/*
	 * Method to create an interrupted timer
	 */
	public void nodeWait(int msec)
	{
		boolean wait = true;
		
		// Wait out the specified amount of time, if interrupted, the loop will run again.
		while (wait)
		{
			try
			{
				synchronized(this) { Thread.sleep(msec); }
				wait = false;
			}
			catch (InterruptedException e)
			{
				// Reserved for Debugging purposes
			}	
		}
	}
	
	/*
	 * Floods the node's neighbours with an incoming HashMap
	 */
	public void flood( HashMap < Integer, HashMap <Integer, Integer>> table) throws IOException
	{
		
		// For all of its neighbours
		Iterator<Integer> iterate = list.keySet().iterator();
		unstable = new ArrayList<Integer>();
		while (iterate.hasNext())
		{
			int nodeSend = iterate.next();
			
			// Adds each node to unstable for ACK check
			unstable.add(nodeSend);
			
			Iterator<Integer> iterator = table.keySet().iterator(); 
			while (iterator.hasNext())
			{
				int currentNode = iterator.next();

				// Construct flood message
				String message = ".flood " + currentNode;
				Iterator<Integer> iterator2 = table.get( (Object) currentNode).keySet().iterator();
				while (iterator2.hasNext())
				{
					int node = iterator2.next();
					int cost = table.get( (Object) currentNode).get( (Object) node);
					
					message += " " + node + " " + cost;		
				}
				
				// Sends the flood message
				byte[] sendData  = new byte[4096];		
				sendData = message.getBytes(); 
				DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IP, nodeSend);
				
				sock.send(sendPacket);
				System.out.println("Message sent from Node (Port " + nodePort + ") to Node (Port " + nodeSend + ")");
			}
		}
	}

	/*
	 * Simulates tracert user interface.
	 * Prints routing table upon request.
	 * Lets user know if a node is unreachable
	 */
	public void send() throws IOException, InterruptedException
	{
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
		
		// Provides on-screen instructions
		System.out.println("\n>>> table : Displays the routing table");
		System.out.println(">>> tracert <port_number>");
		System.out.println(">>> exit : Exits the program\n");
		
		while (true)
		{
			System.out.print(">>> ");
			
			String line = in.readLine();	
			String out[] = line.split(" ");
			
			if (out[0].equals("tracert"))
			{
				int dst = 0;
				
				try { dst = Integer.parseInt(out[1]); }
				catch (NumberFormatException e)
				{
					System.out.println("Input invalid.\n");
					continue;
				}
				
				if (dst == nodePort)
				{
					System.out.println("Cannot trace a route to self.\n");
					continue;
				}
				
				if (!tableLookup (dst))
				{
					System.out.println("Node (Port " + dst + ") not found.\n");
					continue;
				}
				
				// Blocks sendThread until tracert is complete
				tracewait = true;
				
				while (tracewait)				{
					synchronized (this)
					{
						try 
						{ 	
							Thread.sleep(5000);
							System.out.println("Node (Port " + dst + ") unreachable.\n");
							tracewait = false;
						}
						catch (InterruptedException e) 
						{ 
							tracewait = false; 
						}
					}
				}	
			}
			
			// Exits the program
			else if (out[0].equals("exit"))
			{
				shutdown();
			}
			
			// Displays the routing table
			else if (out[0].equals("table"))
			{
				System.out.println("\n" + tableString + "\n");
			}
			
			// Command not recognized
			else
			{
				System.out.println("Command not recognized.\n");
			}	
		}
	}
	
	/*
	 * Looks up the next port along the shortest path to forward the message to destination
	 */
	public boolean tableLookup (int nodeSend) throws IOException
	{
		String message = ".trace " + nodeSend + " " + nodePort + " 0 0";

		int toPort = 0;
		
		try { toPort = routingTable.get((Object) nodeSend).get(0); }
		catch (NullPointerException e) { return false; }

		// Sends the trace message
		byte[] sendData  = new byte[4096];		
		sendData = message.getBytes(); 
		DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IP, toPort);
		System.out.println("\nTracing route to " + nodeSend);
		sock.send(sendPacket);	
		
		return true;
	}
	
	
	public void shutdown () throws IOException
	{
		Iterator<Integer> iterator = list.keySet().iterator();
		while (iterator.hasNext())
		{
			int toPort = iterator.next();
			
			// Sends the shutdown message
			String message = ".shutdown";
			byte[] sendData  = new byte[4096];		
			sendData = message.getBytes(); 
			DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IP, toPort);
			sock.send(sendPacket);	
		}
		
		System.out.println("Program Terminating...");
		System.exit(0);
	}
	
}
