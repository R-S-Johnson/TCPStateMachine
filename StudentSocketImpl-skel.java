import java.net.*;
import java.io.*;
import java.util.Timer;

class StudentSocketImpl extends BaseSocketImpl {

  // SocketImpl data members:
  //   protected InetAddress address;
  //   protected int port;
  //   protected int localport;

  private enum State {
    CLOSED,
    LISTEN,
    SYN_RCVD,
    ESTABLISHED,
    SYN_SENT,
    FIN_WAIT_1,
    CLOSE_WAIT,
    CLOSING,
    FIN_WAIT_2,
    LAST_ACK,
    TIME_WAIT
  }
  private State socketState = State.CLOSED;
  private Demultiplexer demultiplexer;
  private Timer tcpTimer;
  // server variables
  private ServerSocket sSocket;
  private InetAddress serverIP;
  private int serverPort;
  private int ACKnum;
  private int SEQnum;
  // client variables
  private Socket cSocket;
  private InetAddress clientIP;
  private int clientPort;
  // input/output
  private PrintWriter writer;
  private BufferedReader inputBuf;
  private TCPPacket packetBuf;

  // private helper functions //
  // change the state of the tcp machine
  private void changeState(State s) {System.out.println("!!! " + socketState.name() + "->" + s.name());socketState = s;}


  StudentSocketImpl(Demultiplexer D) {  // default constructor
    this.demultiplexer = D;
  }

  /**
   * Connects this socket to the specified port number on the specified host.
   *
   * @param      address   the IP address of the remote host.
   * @param      port      the port number.
   * @exception  IOException  if an I/O error occurs when attempting a
   *               connection.
   */
  public synchronized void connect(InetAddress address, int port) throws IOException{
    serverPort = demultiplexer.getNextAvailablePort();
    clientPort = port;
    clientIP = address;
    SEQnum = 1000;
    try {
      // establish sockets
      sSocket = new ServerSocket(serverPort);
      cSocket = sSocket.accept();
      // establish input and output handlers
      writer = new PrintWriter(cSocket.getOutputStream(), true);
      inputBuf = new BufferedReader(new InputStreamReader(cSocket.getInputStream()));
      // demultiplexer
      demultiplexer = new Demultiplexer(serverPort);
      demultiplexer.registerConnection(clientIP, serverPort, clientPort, this);
      demultiplexer.run();
      // SYN packet
      packetBuf = new TCPPacket(serverPort, clientPort, SEQnum, 0, false, true, false, 0, null);
      TCPWrapper.send(packetBuf, clientIP);
      // state -> syn sent
      changeState(State.SYN_SENT);
      while (socketState != State.ESTABLISHED) {
        wait();
      }  
    }
    catch (IOException i) {
      System.out.println(i);
    }
    catch (InterruptedException i) {
      System.out.println(i);
    }
  }
  
  /**
   * Called by Demultiplexer when a packet comes in for this connection
   * @param p The packet that arrived
   */
  public synchronized void receivePacket(TCPPacket p){
    System.out.println(p.toString());
    if (p.synFlag || p.ackFlag) {
      SEQnum = p.ackNum;
      ACKnum = p.seqNum + 1;
    }
    switch(socketState) {
      case LISTEN: {
        if (p.synFlag) {
          packetBuf = new TCPPacket(serverPort, clientPort, SEQnum, ACKnum, true, true, false, 0, null);
          TCPWrapper.send(packetBuf, clientIP);
          try {
            demultiplexer.unregisterListeningSocket(serverPort, this);
          }
          catch (IOException i) {
            System.out.println(i);
          }
          changeState(State.SYN_RCVD);
        }
      }
      case SYN_RCVD: {
        if (p.ackFlag) {
          changeState(State.ESTABLISHED);
        }
      }
      case SYN_SENT: {
        if (p.ackFlag && p.synFlag) {
          packetBuf = new TCPPacket(serverPort, clientPort, SEQnum, ACKnum, true, false, false, 0, null);
          TCPWrapper.send(packetBuf, clientIP);
          changeState(State.ESTABLISHED);
        }
      }
      case ESTABLISHED: {
        if (p.finFlag) {
          packetBuf = new TCPPacket(serverPort, clientPort, SEQnum, ACKnum, true, false, false, 0, null);
          TCPWrapper.send(packetBuf, clientIP);
          changeState(State.CLOSE_WAIT);
        }
      }
      case FIN_WAIT_1: {
        if (p.finFlag) {
          packetBuf = new TCPPacket(serverPort, clientPort, SEQnum, ACKnum, true, false, false, 0, null);
          TCPWrapper.send(packetBuf, clientIP);
          changeState(State.CLOSING);
        }
        else if (p.ackFlag) {
          changeState(State.FIN_WAIT_2);
        }
      }
      case CLOSING: {
        if (p.ackFlag) {
          changeState(State.TIME_WAIT);
        }
      }
      case FIN_WAIT_2: {
        if (p.finFlag) {
          packetBuf = new TCPPacket(serverPort, clientPort, SEQnum, ACKnum, true, false, false, 0, null);
          TCPWrapper.send(packetBuf, clientIP);
          changeState(State.TIME_WAIT);
        }
      }
      case LAST_ACK: {
        if (p.ackFlag) {
          changeState(State.TIME_WAIT);
        }
      }
      default: {
        // pass
      }
    }
    this.notifyAll();
  }
  
  /** 
   * Waits for an incoming connection to arrive to connect this socket to
   * Ultimately this is called by the application calling 
   * ServerSocket.accept(), but this method belongs to the Socket object 
   * that will be returned, not the listening ServerSocket.
   * Note that localport is already set prior to this being called.
   */
  public synchronized void acceptConnection() throws IOException {
    serverPort = demultiplexer.getNextAvailablePort();
    SEQnum = 1000;
    try {
      // establish sockets
      sSocket = new ServerSocket(serverPort);
      // establish input and output handlers
      writer = new PrintWriter(cSocket.getOutputStream(), true);
      inputBuf = new BufferedReader(new InputStreamReader(cSocket.getInputStream()));
      // demultiplexer
      demultiplexer = new Demultiplexer(serverPort);
      demultiplexer.registerListeningSocket(serverPort, this);
      // wait
      cSocket = sSocket.accept();
      clientPort = cSocket.getPort();
      clientIP = cSocket.getInetAddress();
      // more dem
      demultiplexer.registerConnection(clientIP, serverPort, clientPort, this);
      demultiplexer.run();
      // state -> listening
      changeState(State.LISTEN);
      while (socketState != State.ESTABLISHED || socketState != State.SYN_RCVD) {
        wait();
      }
    }
    catch (IOException i) {
      System.out.println(i);
    }
    catch (InterruptedException i) {
      System.out.println(i);
    }
  }

  
  /**
   * Returns an input stream for this socket.  Note that this method cannot
   * create a NEW InputStream, but must return a reference to an 
   * existing InputStream (that you create elsewhere) because it may be
   * called more than once.
   *
   * @return     a stream for reading from this socket.
   * @exception  IOException  if an I/O error occurs when creating the
   *               input stream.
   */
  public InputStream getInputStream() throws IOException {
    // project 4 return appIS;
    return null;
    
  }

  /**
   * Returns an output stream for this socket.  Note that this method cannot
   * create a NEW InputStream, but must return a reference to an 
   * existing InputStream (that you create elsewhere) because it may be
   * called more than once.
   *
   * @return     an output stream for writing to this socket.
   * @exception  IOException  if an I/O error occurs when creating the
   *               output stream.
   */
  public OutputStream getOutputStream() throws IOException {
    // project 4 return appOS;
    return null;
  }


  /**
   * Closes this socket. 
   *
   * @exception  IOException  if an I/O error occurs when closing this socket.
   */
  public synchronized void close() throws IOException {
    if (socketState == State.ESTABLISHED) {
      socketState = State.FIN_WAIT_1;
    }
    else if (socketState == State.CLOSE_WAIT) {
      socketState = State.LAST_ACK;
    }
    packetBuf = new TCPPacket(serverPort, clientPort, SEQnum, 0, false, false, true, 0, null);
    TCPWrapper.send(packetBuf, clientIP);
  }

  /** 
   * create TCPTimerTask instance, handling tcpTimer creation
   * @param delay time in milliseconds before call
   * @param ref generic reference to be returned to handleTimer
   */
  private TCPTimerTask createTimerTask(long delay, Object ref){
    if(tcpTimer == null)
      tcpTimer = new Timer(false);
    return new TCPTimerTask(tcpTimer, delay, this, ref);
  }


  /**
   * handle timer expiration (called by TCPTimerTask)
   * @param ref Generic reference that can be used by the timer to return 
   * information.
   */
  public synchronized void handleTimer(Object ref){

    // this must run only once the last timer (30 second timer) has expired
    tcpTimer.cancel();
    tcpTimer = null;
  }
}
