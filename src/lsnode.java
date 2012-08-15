import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Main class that also acts as a receive thread. Constructs routing table from message gathered and compute shortest path
 * @author Fernando
 *
 */
public class lsnode 
{
	static HashMap<Integer, Integer> list = new HashMap<Integer, Integer>();
	static HashMap<Integer, HashMap<Integer, Integer>> flood = new HashMap<Integer, HashMap<Integer, Integer>>();
	static HashMap< Integer, ArrayList<Integer> > routingTable = new HashMap< Integer, ArrayList<Integer> >();
	static DatagramSocket nodeSocket = null;
	static InetAddress IP = null;
	static ArrayList<Integer> unstable = new ArrayList<Integer>();
	static String tableString = "";
	static int nodePort = 0;
	static SendThread s = null;
	static Thread sThread = null;
	static boolean last = false;
	static boolean tableComplete = false;
	
	public static void main (String [] args) throws InterruptedException, IOException
	{
		int argc = args.length;

		// Initial conditions that is based on the "last" command
		boolean initiate = false;
		boolean started = false;
		IP = InetAddress.getByName("localhost");
		
		// If number of command is even
		if (argc%2 ==0)
		{
			// Check if "last" command is input correctly
			if (!args[argc-1].equalsIgnoreCase("last"))
			{
				System.out.println("Invalid Last Node Command...\nProgram Terminating.\n");
				System.exit(0);
			}
			argc--;
			initiate = true;
			last = true;
		}
		
		// Prints the start message with neighbors for debugging purposes
		System.out.print("Node (Port " + args[0] + ") started with Neighbors: ");
		for (int i = 1; i < argc; i++)
		{
			unstable.add(Integer.parseInt(args[i]));
			System.out.print(args[i] + " ");
			list.put(Integer.parseInt(args[i++]), Integer.parseInt(args[i]));
		}
		System.out.println("\n");

		nodePort = Integer.parseInt(args[0]);
		
		// Prepares the table for flooding
		flood.put( nodePort , list);
		
		// Creates and binds to socket
		try	{ nodeSocket = new DatagramSocket( nodePort ); }
		catch (BindException e)
		{
			System.out.println("Port already in use...\nProgram terminating.\n");
			System.exit(0);
		} 
		catch (NumberFormatException e) 
		{
			System.out.println("Incorrectly formatted port...\nProgram terminating.\n");
			System.exit(0);
		} 


		s = new SendThread (nodeSocket, list, unstable, nodePort );
		sThread = new Thread(s);	
		
		// Removes itself from unstable port list
		s.unstable.remove((Object) nodePort);
		
		
		// Loop forever
		while (true)
		{
			// If instance is the last node, start the process
			if (initiate)
			{
				sThread.start();
				started = true;
				initiate = false;
				System.out.println("Link status verification started.");
			}
			
			// Receives data
			byte[] receiveData = new byte[1024];
			DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length); 
			nodeSocket.receive(receivePacket); 		   
			String incoming = new String(receivePacket.getData(), 0, receivePacket.getLength());
			int fromPort = receivePacket.getPort();

			String delims = "[ \t]+";
			String in[] = incoming.split(delims);
			
			// If incoming message is a verification request
			if (in[0].equals(".verify"))
			{
				
				// Acknowledge it, add to list if it is a new node
				try { ackVerify ( fromPort, list.get(fromPort) ); }
				catch (NullPointerException e)
				{
					list.put(fromPort, Integer.parseInt(in[1]));
					ackVerify (fromPort, list.get(fromPort) );	
				}
				
				// If costs do not match, display messages and terminate program
				if (!matchCost(fromPort, Integer.parseInt(in[1])))
				{
					System.out.println("Link cost from Node (Port " + nodePort + ") to Node (Port " + fromPort + ") is " + list.get(fromPort));
					System.out.println("Link cost from Node (Port " + fromPort + ") to Node (Port " + nodePort + ") is " + in[1]);
					System.out.println("Link cost does not match. Program terminated.");
					System.exit(0);
				}

				// Upon receipt of a verification, if sendThread hasn't started already, start it
				if (!started)
				{
					sThread.start();
					started = true;
					System.out.println("\nLink status verification started.");
				}
	
				System.out.println("Message sent from Node (Port " + nodePort + ") to Node (Port " + fromPort + ")");
				
			}
			
			
			// If message is an ACK
			if (in[0].equals(".ack"))
			{
				// If ACK is a verification ACK
				if (in[1].equals(".verify"))
				{		
					// If costs do not match, display messages and terminate program
					if (!matchCost(fromPort, Integer.parseInt(in[2])))
					{
						System.out.println("Link cost from Node (Port " + nodePort + ") to Node (Port " + fromPort + ") is " + list.get(fromPort));
						System.out.println("Link cost from Node (Port " + fromPort + ") to Node (Port " + nodePort + ") is " + in[2]);
						System.out.println("Link cost does not match. Program terminated.");
						System.exit(0);
					}
					// Else upon receipt of verification ACK, remove port from unstable list.
					s.unstable.remove( (Object) fromPort );					
				}
				
				// If ACK is a flood ACK, then restart the 5 second timer, and remove source port from unstable list
				if (in[1].equals(".flood"))
				{
					synchronized (sThread)	{ sThread.interrupt(); }
					s.unstable.remove( (Object) fromPort);
				}
				
				// If ACK is a trace message, display it if currentNode is the intended recipient, forward it if not
				if (in[1].equals(".trace"))
				{
					int dst = Integer.parseInt(in[2]);
					
					if (dst == nodePort)
					{
						int index = 13 + in[2].length();
						String subString = incoming.substring(index);
						System.out.println(subString);
					}
					else
					{
						// Forward the trace message
						byte[] sendData  = new byte[4096];		
						sendData = incoming.getBytes(); 
						DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IP, routingTable.get((Object) dst).get(0));
						nodeSocket.send(sendPacket);
					}	
					continue;
				}
				
				// If ACK is an endTrace message, forward it to the recipient. Display it upon reception
				if (in[1].equals(".endtrace"))
				{
					int dst = Integer.parseInt(in[2]);
					
					if (dst == nodePort)
					{
						int index = 16 + in[2].length();
						String subString = incoming.substring(index);
						System.out.println(subString);
						System.out.println("Trace complete.\n");
						
						synchronized (sThread)	{ sThread.interrupt(); }
					}
					else
					{
						// Forward the trace message
						byte[] sendData  = new byte[4096];		
						sendData = incoming.getBytes(); 
						DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IP, routingTable.get((Object) dst).get(0));
						nodeSocket.send(sendPacket);
					}	
					
					continue;
				}	
			}
			
			// Initiate network flooding
			if ( in[0].equals(".startflood"))
			{
				s.flood(flood);
				continue;
			}

			// If message is a flooding message
			if (in[0].equals(".flood"))
			{
				// Restart timer
				synchronized (sThread)	{ sThread.interrupt(); }
				
				int size = flood.size();
				
				// Construct temparory HashMap from input
				HashMap<Integer, Integer> temp = new HashMap <Integer, Integer>();	
				for (int i = 2; i < in.length; i++)
				{
					temp.put(Integer.parseInt(in[i++]), Integer.parseInt(in[i]));
				}
					
				// Add to table
				flood.put( Integer.parseInt(in[1]), temp);
				
				// Sends ACK back
				String message = ".ack .flood";				
				byte[] sendData  = new byte[4096];		
				sendData = message.getBytes();
				DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IP, fromPort);			
				nodeSocket.send(sendPacket);	
				
				// If table size changed, update all neighbors
				if (flood.size() != size)
				{
					s.flood(flood);
				}
			}
			
			// Ends message flooding and initiates Table construction
			if (in[0].equals(".endflood"))
			{
				System.out.println("Message flooding finished.");
				System.out.println("\nNode (Port " + nodePort + ") received the following link-state information:");
				printFlood();
				System.out.println("\nNode (Port " + nodePort + ") starts routing table construction.");
				buildTable();
				continue;
			}
			
			// sender has finished constructing table
			if (in[0].equals(".tabledone"))
			{
				int dst = Integer.parseInt(in[1]);
				int src = Integer.parseInt(in[2]);
				
				// If current table is not complete, send a message back
				if (!tableComplete)
				{
					// Sends ACK back
					String message = ".notdone " + incoming;			
					byte[] sendData  = new byte[4096];		
					sendData = message.getBytes();
					DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IP, fromPort);			
					nodeSocket.send(sendPacket);
					continue;
				}
				
				// Forward the message onto other ports
				else if (dst != nodePort)
				{
					// Sends ACK back				
					byte[] sendData  = new byte[4096];		
					sendData = incoming.getBytes();
					DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IP, routingTable.get(dst).get(0));			
					nodeSocket.send(sendPacket);
					continue;
					
				}
				else if (unstable.size() != 0)
				{
					unstable.remove((Object) src);
					//System.out.println(unstable.toString());
					
					// Once all nodes in the network is finish, initiate tracert simulation
					if (unstable.size() == 0)
					{
						if (!last)
							synchronized (sThread)	{ sThread.interrupt(); }
						else
							sendFarthest();
					}	
				}	
				continue;	
			}
			
			// if sender is not ready yet, wait 500 msec, then resend
			if (in[0].equals(".notdone"))
			{
				Thread.sleep(500);
				
				String message = incoming.substring(9);		
				byte[] sendData  = new byte[4096];		
				sendData = message.getBytes();
				DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IP, fromPort);			
				nodeSocket.send(sendPacket);
				
				continue;	
			}
			
			// If message is a trace message
			if (in[0].equals(".trace"))
			{				
				int dst = Integer.parseInt(in[1]);
				int src = Integer.parseInt(in[2]);
				
				// Increase hop count by one, and cost by the appropriate amount
				int hop = Integer.parseInt(in[3]) + 1;
				int cost = Integer.parseInt(in[4]) + list.get((Object) fromPort);

				if (dst == nodePort)
				{
					System.out.println("Message received at Node (Port " + nodePort + ") from Node (Port " + src + ")");
					System.out.print(">>> ");
					
					// Sends ACK back
					String message = ".ack .endtrace " + src + " " + hop + "\t" + fromPort + " to " + nodePort + " (Cost: " + cost + ")";				
					byte[] sendData  = new byte[4096];		
					sendData = message.getBytes();
					DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IP, fromPort);			
					nodeSocket.send(sendPacket);
				}
				else
				{
					ArrayList<Integer> temp = routingTable.get((Object) dst);
					int forwardNode = temp.get(0);
					
					// Forward the trace message
					String message = ".trace " + dst + " " + src + " " + hop + " " + cost;					
					byte[] sendData  = new byte[4096];		
					sendData = message.getBytes(); 
					DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IP, forwardNode);
					nodeSocket.send(sendPacket);
					
					System.out.println("Forwarding trace (Port " + dst + " to Port " + src + ").");
					System.out.print (">>> ");

					// ACKs the sender
					message = ".ack .trace " + src + " " + hop + "\t" + fromPort + " to " + nodePort + " (Cost: " + cost + ")";
					sendData  = new byte[4096];		
					sendData = message.getBytes(); 
					sendPacket = new DatagramPacket(sendData, sendData.length, IP, fromPort);
					nodeSocket.send(sendPacket);
				}
				
				continue;
			}
			
			
			if (in[0].equals(".shutdown"))
			{
				shutdown();
			}
			
			System.out.println("Message received at Node (Port " + nodePort + ") from Node (Port " + fromPort + ")");
		}
	}
		
	/*
	 * Checks if two costs match
	 */
	public static boolean matchCost (int port, int cost)
	{
		int ref = list.get(port);
		
		if (cost == ref)
			return true;
		
		return false;
	}
	
	/*
	 * Sends a verification ACK back to sender port
	 */
	public static void ackVerify(int port, int cost) throws IOException
	{
		// Constructs the ACK message to verification
		String message = ".ack .verify " + cost;
		byte[] sendData  = new byte[1024];		
		sendData = message.getBytes(); 
		DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IP, port);
		
		// Sends packet
		nodeSocket.send(sendPacket);
		System.out.println("Message sent from Node (Port " + nodePort + ") to Node (Port " + port + ")");
	}

	/*
	 * function to handle table construction and initiate route tracing if node is the last node
	 */
	public static void buildTable() throws IOException, InterruptedException
	{
		// Constructs the first path
		ArrayList<Integer> path = new ArrayList<Integer>();
		path.add(nodePort);	
		HashMap< ArrayList<Integer>, Integer> table = new HashMap< ArrayList<Integer>, Integer>();
		table.put(path, 0);

		// Recursively find all possible paths in the network
		table = findPaths(table);

		// Group paths into hops
		HashMap<Integer, HashMap<ArrayList<Integer> , Integer>> routingHops = new HashMap<Integer, HashMap<ArrayList<Integer>, Integer>>();		
		Iterator<ArrayList<Integer>> iterator = table.keySet().iterator();
		while (iterator.hasNext())
		{
			ArrayList<Integer> currentPath = iterator.next();	
			if (currentPath.size() > 1)
			{
				int hops = currentPath.size()-2;
				HashMap<ArrayList<Integer>, Integer> tempPath = null;
				
				try 
				{ 
					tempPath = routingHops.get((Object) hops); 
					tempPath.put(currentPath, table.get(currentPath));
				}
				catch (NullPointerException e)
				{
					tempPath = new HashMap<ArrayList<Integer>, Integer>();
					tempPath.put(currentPath, table.get(currentPath));
				}
				
				routingHops.put(hops, tempPath);
			}
		}
				
		// Construct and print the table to string;
		routingTable(routingHops);	
		
		// Table is saved to memory and can be reprinted upon request
		System.out.println(tableString);
		
		System.out.println("Node (Port " + nodePort + ") finished routing table construction.\n");
			
		// Updates sendThread's routing table
		tableComplete = true;
		unstable = new ArrayList<Integer>();
		unstable.addAll(routingTable.keySet());

		s.routingTable.putAll(routingTable);
		s.tableString = tableString;
		
		tableDone();
	}
	
	/*
	 *  Constructs routing table and print it to string
	 */
	public static void routingTable (HashMap<Integer, HashMap<ArrayList<Integer> , Integer>> routingHops)
	{
		// Creates the top row
		String header = "Step\tN'";
		String output = "";
		String divider = "--------";

		routingTable = new HashMap<Integer, ArrayList<Integer>>();
		ArrayList<Integer> dstPorts = new ArrayList<Integer>();
		dstPorts.addAll(flood.keySet());
		Collections.sort(dstPorts);
		
		// Set the length of the divider
		int length = (dstPorts.toString().length())/8 + 1;
		for (int i = 0; i < length; i++)
		{
			header += "\t";
			divider += "--------";
		}
		
		dstPorts.remove((Object) nodePort);
		for (int i = 0; i < dstPorts.size(); i++)
		{
			output += dstPorts.get(i) + "\t\t\t";
			divider += "------------------------";
		}
		tableString += divider + "\n" + header + output + "\n" + divider + "\n";
		
		// For each step (hop)
		ArrayList<Integer> shortestPath  = new ArrayList<Integer>();
		shortestPath.add(0,  nodePort);
		for (int i = 0; i < routingHops.size(); i++)
		{
			int smallestCost = 0;
			output = i + "\t" + shortestPath;
			int newLength = length - ((shortestPath.toString().length())/8);
			for (int k = 0; k < newLength; k++)
			{
				output += "\t";
			}
			
			// Go through each hop and construct routing table
			HashMap<ArrayList<Integer> , Integer> tempHops = routingHops.get(i);
			for (int j= 0; j < dstPorts.size(); j++)
			{
				int currentNode = dstPorts.get(j);
				
				if (shortestPath.contains(currentNode))
				{
					continue;
				}
				
				boolean nodeFound = false;
				boolean changed = false;
				
				// Look at all the possible routes at the specified hop
				Iterator<ArrayList<Integer>> iterator2 = tempHops.keySet().iterator();
				while (iterator2.hasNext())
				{
					ArrayList<Integer> tempPath = iterator2.next();
					
					int dstNode = tempPath.get(tempPath.size()-1);
					int nextNode = tempPath.get(1);
					int cost = tempHops.get(tempPath);
					
					// If destination node is same at the node of interest
					if ( dstNode == currentNode)
					{
						ArrayList<Integer> tempEntry = new ArrayList<Integer>();
						tempEntry.add(nextNode);
						tempEntry.add(cost);
							
						// determine the smallest cost at current iteration
						if ((smallestCost != 0) && (cost < smallestCost))
						{
							smallestCost = cost;
							shortestPath.remove(i+1);
							shortestPath.add(currentNode);
						}
						else if (smallestCost == 0)
						{
							smallestCost = cost;
							shortestPath.add(currentNode);
						}

						if (routingTable.containsKey(dstNode))
						{
							int tempCost = routingTable.get(dstNode).get(1);
							
							if (cost < tempCost)
							{
								routingTable.put(dstNode, tempEntry);
								changed = true;
							}
						}
						else if (!nodeFound)
						{	
							routingTable.put(dstNode, tempEntry);
							changed = true;
						}
						nodeFound = true;
					}		
				}	
				
				if (!changed && routingTable.containsKey(currentNode))
				{
					int cost = routingTable.get(currentNode).get(1);
					
					// determine the smallest cost at current iteration
					if ((smallestCost != 0) && (cost < smallestCost))
					{
						smallestCost = cost;
						shortestPath.remove(i+1);
						shortestPath.add(currentNode);
					}
					else if (smallestCost == 0)
					{
						smallestCost = cost;
						shortestPath.add(currentNode);
					}
				}
			}
			
			// Calls helper class to construct output string at this iteration
			tableString += output + printToTable(dstPorts, shortestPath, routingTable) + "\n" + divider + "\n";
		}
		
		// Constructs the last row which signifies the termination of table
		tableString += routingHops.size() + "\t" + shortestPath.toString() + "\n" + divider + "\n";	
	}
	
	/*
	 * Helper method that prints after each iteration
	 */
	public static String printToTable(final ArrayList<Integer> dstNode, final ArrayList<Integer> shortestPath, final HashMap<Integer, ArrayList<Integer>> table)
	{
		String output = "";
		
		// Omit the last node of the shortest path
		ArrayList<Integer> tempSP = new ArrayList<Integer>();
		tempSP.addAll(shortestPath);
		tempSP.remove(tempSP.size()-1);
		
		// Goes through the each destination node and print out the next node and cost at current iteration
		for (int i = 0; i < dstNode.size(); i++)
		{
			// If node is part of shortest path, entry is empty
			if (tempSP.contains(dstNode.get(i)))
			{
				output += "\t\t\t";
				continue;
			}
			
			else if (table.containsKey(dstNode.get(i)))
			{
				String tempString = table.get(dstNode.get(i)).get(1) + ", " + table.get(dstNode.get(i)).get(0);	
				if (tempString.length() > 7)
					output += tempString + "\t\t";
				else
					output += tempString + "\t\t\t";
			}
			
			// If node does is unreachable, prints N/A
			else
				output += "N/A\t\t\t";
		}
		return output;
	}
	
	/*
	 * Use the network information gathered from flooding to determine 
	 * all possible paths from current node to any other nodes in the network.
	 * 
	 * Recursively adds the paths to the table
	 */
	public static HashMap< ArrayList<Integer>, Integer> findPaths(final HashMap< ArrayList<Integer>, Integer> table)
	{
		HashMap< ArrayList<Integer>, Integer> tempTable = new HashMap< ArrayList<Integer>, Integer>();
		tempTable.putAll(table);
		
		Iterator<ArrayList<Integer>> iterator = table.keySet().iterator(); 
		while (iterator.hasNext())
		{
			final ArrayList<Integer> path = iterator.next();
			HashMap <Integer, Integer> tempHash = flood.get((Object) path.get(path.size()-1));

			Iterator<Integer> iterator2 = tempHash.keySet().iterator();
			while (iterator2.hasNext())
			{
				int currentNode = iterator2.next();
				
				ArrayList<Integer> tempPath = new ArrayList<Integer>();
				tempPath.addAll(path);
				
				if ( !path.contains((Object) currentNode) ) 
				{
					tempPath.add(currentNode);
					int addCost = tempHash.get((Object) currentNode) + table.get(path);
					
					tempTable.put(tempPath, addCost);
				}
			}	
		}

		if (table.size() == tempTable.size())
			return tempTable;
		else
			return findPaths(tempTable);
	}
	
	/*
	 * Notifies all nodes in the network that current node has completed its routing table.
	 */
	public static void tableDone() throws IOException
	{
		ArrayList<Integer> toSend = new ArrayList<Integer>();
		toSend.addAll(routingTable.keySet());
		
		for (int i = 0; i < toSend.size(); i++)
		{
			String message = ".tabledone " + toSend.get(i) + " " + nodePort;
			byte[] sendData  = new byte[4096];		
			sendData = message.getBytes(); 
			DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IP, routingTable.get((Object) toSend.get(i)).get(0));
			nodeSocket.send(sendPacket);
		}
		
	}
		
	/*
	 * Finds the farthest node, and send a trace message to it along the shortest path
	 */
	public static void sendFarthest() throws IOException
	{
		int furthestCost = 0;
		int furthestNode = 0;
		int nextNode = 0;
		
		// Find the farthest node
		Iterator<Integer> iterator = routingTable.keySet().iterator();
		while (iterator.hasNext())
		{
			int tempNode = iterator.next();
			int tempNext = routingTable.get((Object) tempNode).get(0);
			int tempCost = routingTable.get((Object) tempNode).get(1);
			
			if (tempCost > furthestCost)
			{
				furthestCost = tempCost;
				furthestNode = tempNode;
				nextNode = tempNext;
			}
		}

		// creates trace packet
		String message = ".trace " + furthestNode + " " + nodePort + " 0 0"; 
		byte[] sendData  = new byte[1024];		
		sendData = message.getBytes(); 
		DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IP, nextNode);
		
		// Sends packet
		System.out.println("Tracing route to " + furthestNode);	
		nodeSocket.send(sendPacket);	
	}
	
	/* Sends shutdown message to neighbours */
	public static void shutdown () throws IOException
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
			nodeSocket.send(sendPacket);	
		}
		
		System.out.println("Program Terminating...");
		System.exit(0);
	}
	
	/*
	 * Prints a summary of link-state information received from message flooding (Debugging purposes)
	 */
	public static void printFlood ()
	{
		Iterator<Integer> iterator = flood.keySet().iterator(); 
		while (iterator.hasNext())
		{
			int sourceNode = iterator.next();

			if (sourceNode != nodePort)
			{
				String output = "source Node (Port " + sourceNode + "):";
				
				HashMap<Integer, Integer> temp = flood.get((Object) sourceNode);
				Iterator<Integer> iterator2 = temp.keySet().iterator();
				
				String header = " Neighbors Node (Port ";
				
				while (iterator2.hasNext())
				{
					int neighbourNode = iterator2.next();
					
					output += header + neighbourNode + ") (Cost " + temp.get((Object) neighbourNode) + ")";
					
					header = ", Neighbors Node (Port ";
				}				
				System.out.println(output);
			}
		}
		System.out.println();
	}
}
