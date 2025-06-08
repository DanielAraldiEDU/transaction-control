import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

public class Main {
  private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
  // Random number generator for simulating transaction delays
  private static final Random random = new Random();
  private static final int NUM_THREADS = 5;
  private static final DeadlockDetector deadlockDetector = new DeadlockDetector();

  // Resource shared X and Y
  private static final DataItem resourceX = new DataItem("X");
  private static final DataItem resourceY = new DataItem("Y");

  // Map of active transactions (for timestamp control)
  private static final ConcurrentHashMap<Integer, Transaction> activeTransactions = new ConcurrentHashMap<>();

  public static void main(String[] args) {
    System.out.println("=== SIMULAÇÃO DE DEADLOCK COM WAIT-DIE ===\n");

    ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS + 1);

    // This thread will periodically check for deadlocks
    executor.submit(deadlockDetector);

    // Create and start the main transaction threads
    for (int i = 1; i <= NUM_THREADS; i++) {
      Transaction transaction = new Transaction(i);
      activeTransactions.put(i, transaction);
      executor.submit(transaction);
    }

    // Wait a time to allow transactions to start
    try {
      Thread.sleep(30000); // 30 seconds
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Finalize execution
    deadlockDetector.stop();
    executor.shutdownNow();
    System.out.println("\n=== FIM DA SIMULAÇÃO ===");
  }

  // Log method with timestamp
  private static void logMessage(String threadName, String message) {
    String timestamp = LocalTime.now().format(timeFormatter);
    System.out.println("[" + timestamp + "] T(" + threadName + ") " + message);
  }

  // Class that represents a data item with locking
  static class DataItem {
    private final String itemId;
    private volatile boolean valorLock = false;
    private volatile Transaction transacao = null;
    private final Queue<Transaction> fila = new ConcurrentLinkedQueue<>();
    private final ReentrantLock internalLock = new ReentrantLock();

    public DataItem(String itemId) {
      this.itemId = itemId;
    }

    public boolean tryLock(Transaction transaction) {
      internalLock.lock();
      try {
        if (!valorLock) {
          // Resource available
          valorLock = true;
          transacao = transaction;
          logMessage(transaction.getId() + "", "obtém o bloqueio do recurso " + itemId);
          return true;
        } else {
          // Resource occupied - apply wait-die
          if (transaction.getTimestamp() < transacao.getTimestamp()) {
            // Older transaction waits
            fila.offer(transaction);
            logMessage(transaction.getId() + "", "está esperando pelo recurso " + itemId + " (wait-die: mais antiga)");
            return false;
          } else {
            // The newest transaction is dead
            logMessage(transaction.getId() + "", "é finalizada em virtude de deadlock detectado (wait-die: mais nova)");
            transaction.abort();
            return false;
          }
        }
      } finally {
        internalLock.unlock();
      }
    }

    public void unlock(Transaction transaction) {
      internalLock.lock();
      try {
        if (valorLock && transacao == transaction) {
          logMessage(transaction.getId() + "", "libera (unlock) o recurso " + itemId);
          valorLock = false;
          transacao = null;

          // Notify the next transaction in the queue
          Transaction next = fila.poll();
          if (next != null && !next.isAborted()) {
            synchronized (next) {
              next.notify();
            }
          }
        }
      } finally {
        internalLock.unlock();
      }
    }

    public String getItemId() {
      return itemId;
    }

    public boolean isLocked() {
      return valorLock;
    }

    public Transaction getCurrentTransaction() {
      return transacao;
    }

    public Queue<Transaction> getQueue() {
      return fila;
    }
  }

  // Class that represents a transaction (thread)
  static class Transaction implements Runnable {
    private final int id;
    private final long timestamp;
    private volatile boolean aborted = false;
    private volatile boolean finished = false;

    public Transaction(int id) {
      this.id = id;
      this.timestamp = System.nanoTime();
    }

    @Override
    public void run() {
      logMessage(id + "", "entra em execução (timestamp: " + timestamp + ")");

      while (!aborted && !finished) {
        try {
          executeTransaction();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }

      if (!aborted) {
        logMessage(id + "", "finaliza sua execução");
      }
    }

    private void executeTransaction() throws InterruptedException {
      // Initial time
      randomWait();
      if (aborted)
        return;

      // Try to obtain lock(X)
      boolean gotX = false;
      while (!gotX && !aborted) {
        gotX = resourceX.tryLock(this);
        if (!gotX && !aborted) {
          synchronized (this) {
            wait(1000); // Wait for notification or timeout
          }
        }
      }

      if (aborted)
        return;

      randomWait();
      if (aborted) {
        resourceX.unlock(this);
        return;
      }

      // Try to obtain lock(Y)
      boolean gotY = false;
      while (!gotY && !aborted) {
        gotY = resourceY.tryLock(this);
        if (!gotY && !aborted) {
          synchronized (this) {
            wait(1000); // Wait for notification or timeout
          }
        }
      }

      if (aborted) {
        resourceX.unlock(this);
        return;
      }

      // Critical work
      randomWait();
      if (aborted) {
        resourceY.unlock(this);
        resourceX.unlock(this);
        return;
      }

      // unlock(X)
      resourceX.unlock(this);

      randomWait();
      if (aborted) {
        resourceY.unlock(this);
        return;
      }

      // unlock(Y)
      resourceY.unlock(this);

      randomWait();
      if (aborted)
        return;

      commit();
    }

    private void randomWait() throws InterruptedException {
      if (!aborted) {
        // Time between 500ms and 1500ms
        int waitTime = random.nextInt(1000) + 500;
        Thread.sleep(waitTime);
      }
    }

    private void commit() {
      if (!aborted) {
        logMessage(id + "", "executa commit - transação finalizada com sucesso");
        finished = true;
        activeTransactions.remove(id);
      }
    }

    public void abort() {
      aborted = true;
      logMessage(id + "", "transação abortada - reiniciando...");

      // Remove from wait resource queue
      resourceX.getQueue().remove(this);
      resourceY.getQueue().remove(this);

      // Restart transaction after a delay
      new Thread(() -> {
        try {
          // Sleep between 1 and 3 seconds
          Thread.sleep(random.nextInt(2000) + 1000);
          Transaction newTransaction = new Transaction(id);
          activeTransactions.put(id, newTransaction);
          new Thread(newTransaction).start();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }).start();
    }

    public int getId() {
      return id;
    }

    public long getTimestamp() {
      return timestamp;
    }

    public boolean isAborted() {
      return aborted;
    }

    public boolean isFinished() {
      return finished;
    }
  }

  // Deadlock detector using wait-for graph
  static class DeadlockDetector implements Runnable {
    private volatile boolean running = true;

    @Override
    public void run() {
      while (running) {
        try {
          // Check every 2 seconds
          Thread.sleep(2000);
          detectDeadlock();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }

    private void detectDeadlock() {
      // Build wait-for graph
      Map<Transaction, Set<Transaction>> waitForGraph = new HashMap<Transaction, Set<Transaction>>();

      // Analyze resource X
      if (resourceX.isLocked()) {
        Transaction holder = resourceX.getCurrentTransaction();
        for (Transaction waiter : resourceX.getQueue()) {
          if (!waiter.isAborted()) {
            waitForGraph.computeIfAbsent(waiter, k -> new HashSet<>()).add(holder);
          }
        }
      }

      // Analyze resource Y
      if (resourceY.isLocked()) {
        Transaction holder = resourceY.getCurrentTransaction();
        for (Transaction waiter : resourceY.getQueue()) {
          if (!waiter.isAborted()) {
            waitForGraph.computeIfAbsent(waiter, k -> new HashSet<>()).add(holder);
          }
        }
      }

      // Detect cycles in the graph
      Set<Transaction> visited = new HashSet<>();
      Set<Transaction> recursionStack = new HashSet<>();

      for (Transaction transaction : waitForGraph.keySet()) {
        if (!visited.contains(transaction)) {
          if (hasCycle(transaction, waitForGraph, visited, recursionStack)) {
            logMessage("DeadlockDetector", "DEADLOCK DETECTADO! Aplicando wait-die...");
            // The wait-die is already applied automatically in tryLock
            break;
          }
        }
      }
    }

    // Cycle detection in the wait-for graph
    // T1 → {T2} (T1 waits T2)
    // T2 → {T1} (T2 waits T1)
    private boolean hasCycle(Transaction node, Map<Transaction, Set<Transaction>> graph,
        Set<Transaction> visited, Set<Transaction> recursionStack) {
      visited.add(node);
      recursionStack.add(node);

      Set<Transaction> neighbors = graph.get(node);
      if (neighbors != null) {
        for (Transaction neighbor : neighbors) {
          if (!visited.contains(neighbor)) {
            // Case 1: Neighbor has never been visited - recursion
            if (hasCycle(neighbor, graph, visited, recursionStack)) {
              return true;
            }
          } else if (recursionStack.contains(neighbor)) {
            // Case 2: CYCLE DETECTED!
            return true;
          }
        }
      }

      recursionStack.remove(node);
      return false;
    }

    public void stop() {
      running = false;
    }
  }
}