package beans;

import java.io.*;
import java.net.*;
import java.util.*;

public class FtpServer {

	private static HashMap<String, String> users = new HashMap<>();
	private static final int CONTROL_PORT = 21;

	static {
		getUsers().put("Pablo", "1234");
		getUsers().put("user2", "password2");
		getUsers().put("Antonio", "desi");
	}

	public static void main(String[] args) throws IOException {
		ServerSocket controlSocket = new ServerSocket(CONTROL_PORT);
		System.out.println("FTP Server started on port " + CONTROL_PORT);

		while (true) {
			Socket clientSocket = controlSocket.accept();
			System.out.println("Client connected from " + clientSocket.getInetAddress().getHostAddress());

			// Start a new thread to handle the client connection
			new Thread(new FtpClientHandler(clientSocket)).start();
		}
	}

	public static HashMap<String, String> getUsers() {
		return users;
	}

	public static void setUsers(HashMap<String, String> users) {
		FtpServer.users = users;
	}
}

class FtpClientHandler implements Runnable {

	private Socket clientSocket;
	private static final int DATA_PORT = 20;

	public FtpClientHandler(Socket clientSocket) {
		this.clientSocket = clientSocket;
	}

	@Override
	public void run() {
		try {
			DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream());
			BufferedReader br = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

			dos.writeBytes("220 Service ready for new user. Enter USER <username> and PASS <password> to log in.\r\n");
			String user = null;

			InetAddress dataClientAddress = null;
			int dataClientPort = -1;

			// Now, instead of a while (true) loop, we'll loop until the client disconnects
			while (true) {
				String command = br.readLine();
				if (command == null) {
					break;
				}

				String[] commandParts = command.split(" ");
				switch (commandParts[0].toUpperCase()) {
				case "LIST":
					String pathname = commandParts.length > 1 ? commandParts[1] : ".";
					sendFileList(dos, pathname, dataClientAddress, dataClientPort);
					break;

				case "RETR":
					sendFile(dos, commandParts, dataClientAddress, dataClientPort);
					break;

				case "PORT":
					String[] hostPort = parseHostAndPort(commandParts[1]);
					if (hostPort != null) {
						dataClientAddress = InetAddress
								.getByName(String.join(".", hostPort[0], hostPort[1], hostPort[2], hostPort[3]));
						dataClientPort = Integer.parseInt(hostPort[4]) * 256 + Integer.parseInt(hostPort[5]);
						dos.writeBytes("200 Command okay.\r\n");
					} else {
						dos.writeBytes("501 Syntax error in parameters or arguments.\r\n");
					}
					break;
				case "STOR":
					if (commandParts.length < 2) {
						dos.writeBytes("501 Syntax error in parameters or arguments.\r\n");
						return;
					}
					String filePath = commandParts[1];
					receiveFile(dos, filePath, dataClientAddress, dataClientPort);
					break;

				case "USER":
					user = commandParts[1];
					dos.writeBytes("331 User name okay, need password.\r\n");
					break;

				case "PASS":
					if (user == null) {
						dos.writeBytes("503 Bad sequence of commands.\r\n");
					} else if (!checkUserPassword(user, commandParts[1])) {
						dos.writeBytes("530 Not logged in.\r\n");
					} else {
						dos.writeBytes("230 User logged in, proceed.\r\n");
						user = null; // Reset for next session
						// Exit this loop and proceed to your original command processing loop
						break;
					}
					break;

				default:
					dos.writeBytes("500 Invalid command.\r\n");
				}
			}

			clientSocket.close();
			System.out.println("Client disconnected. Waiting for new connection...\n");
		} catch (IOException e) {
			System.err.println("Client disconnected unexpectedly: " + e.getMessage());
		}
	}

	private static boolean checkUserPassword(String user, String password) {
		String validPassword = FtpServer.getUsers().get(user);
		return validPassword != null && validPassword.equals(password);
	}

	private static void sendFileList(DataOutputStream dos, String pathname, InetAddress dataClientAddress,
			int dataClientPort) throws IOException {
		File directory = new File(pathname);
		if (directory.exists() && directory.isDirectory()) {
			dos.writeBytes("150 File status okay; about to open data connection.\r\n");

			try (ServerSocket serverDataSocket = new ServerSocket(DATA_PORT);
					Socket dataClientSocket = serverDataSocket.accept();
					DataOutputStream dataDos = new DataOutputStream(dataClientSocket.getOutputStream())) {

				String[] fileNames = directory.list();
				for (String fileName : fileNames) {
					dataDos.writeBytes(fileName + "\r\n");
				}
			} catch (IOException e) {
				dos.writeBytes("425 Can't open data connection.\r\n");
			}
			dos.writeBytes("226 Closing data connection. Requested file action successful.\r\n");
		} else {
			dos.writeBytes("550 Requested action not taken. File unavailable.\r\n");
		}
	}

	private static void sendFile(DataOutputStream dos, String[] commandParts, InetAddress dataClientAddress,
			int dataClientPort) throws IOException {
		if (commandParts.length < 2) {
			dos.writeBytes("501 Syntax error in parameters or arguments.\r\n");
			return;
		}

		String pathname = commandParts[1];
		File file = new File(pathname);

		if (file.exists() && file.isFile()) {
			dos.writeBytes("150 File status okay; about to open data connection.\r\n");

			try (ServerSocket serverDataSocket = new ServerSocket(DATA_PORT);
					Socket dataClientSocket = serverDataSocket.accept();
					DataOutputStream dataDos = new DataOutputStream(dataClientSocket.getOutputStream());
					FileInputStream fis = new FileInputStream(file)) {

				byte[] buffer = new byte[4096];
				int bytesRead;

				while ((bytesRead = fis.read(buffer)) != -1) {
					dataDos.write(buffer, 0, bytesRead);
				}
			} catch (IOException e) {
				dos.writeBytes("425 Can't open data connection.\r\n");
			}
			dos.writeBytes("226 Closing data connection. Requested file action successful.\r\n");
		} else {
			dos.writeBytes("550 Requested action not taken. File unavailable.\r\n");
		}
	}

	private static String[] parseHostAndPort(String hostPort) {
		String[] parts = hostPort.split(",");
		if (parts.length == 6) {
			return parts;
		}
		return null;
	}
	
	private static void receiveFile(DataOutputStream dos, String filePath, InetAddress dataClientAddress,
			int dataClientPort) throws IOException {

		dos.writeBytes("150 File status okay; about to open data connection.\r\n");

		try (ServerSocket serverDataSocket = new ServerSocket(DATA_PORT);
				Socket dataClientSocket = serverDataSocket.accept();
				DataInputStream dataDis = new DataInputStream(dataClientSocket.getInputStream());
				FileOutputStream fos = new FileOutputStream(filePath)) {

			byte[] buffer = new byte[4096];
			int bytesRead;

			while ((bytesRead = dataDis.read(buffer)) != -1) {
				fos.write(buffer, 0, bytesRead);
			}
		} catch (IOException e) {
			dos.writeBytes("425 Can't open data connection.\r\n");
		}
		dos.writeBytes("226 Closing data connection. Requested file action successful.\r\n");
	}

}
