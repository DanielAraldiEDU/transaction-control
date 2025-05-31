import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

public class Main {
  private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
  private static final Random random = new Random();
  private static final int NUM_THREADS = 5;
  private static final DeadlockDetector deadlockDetector = new DeadlockDetector();

  // Recursos compartilhados X e Y
  private static final DataItem resourceX = new DataItem("X");
  private static final DataItem resourceY = new DataItem("Y");

  // Mapa de transações ativas (para controle de timestamp)
  private static final ConcurrentHashMap<Integer, Transaction> activeTransactions = new ConcurrentHashMap<>();

  public static void main(String[] args) {
    System.out.println("=== SIMULAÇÃO DE DEADLOCK COM WAIT-DIE ===\n");

    ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS + 1);

    // Thread para detecção de deadlock
    executor.submit(deadlockDetector);

    // Criar e iniciar N threads de transação
    for (int i = 1; i <= NUM_THREADS; i++) {
      Transaction transaction = new Transaction(i);
      activeTransactions.put(i, transaction);
      executor.submit(transaction);
    }

    // Aguardar um tempo para observar o comportamento
    try {
      Thread.sleep(30000); // 30 segundos
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Finalizar execução
    deadlockDetector.stop();
    executor.shutdownNow();
    System.out.println("\n=== FIM DA SIMULAÇÃO ===");
  }

  // Método para log com timestamp
  private static void logMessage(String threadName, String message) {
    String timestamp = LocalTime.now().format(timeFormatter);
    System.out.println("[" + timestamp + "] T(" + threadName + ") " + message);
  }

  // Classe que representa um item de dados com bloqueio
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
          // Recurso disponível
          valorLock = true;
          transacao = transaction;
          logMessage(transaction.getId() + "", "obtém o bloqueio do recurso " + itemId);
          return true;
        } else {
          // Recurso ocupado - aplicar wait-die
          if (transaction.getTimestamp() < transacao.getTimestamp()) {
            // Transação mais antiga espera
            fila.offer(transaction);
            logMessage(transaction.getId() + "", "está esperando pelo recurso " + itemId + " (wait-die: mais antiga)");
            return false;
          } else {
            // Transação mais nova "morre"
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

          // Notificar próxima transação na fila
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

  // Classe que representa uma transação (thread)
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
      // random(t) - tempo inicial
      randomWait();
      if (aborted)
        return;

      // Tentar obter lock(X)
      boolean gotX = false;
      while (!gotX && !aborted) {
        gotX = resourceX.tryLock(this);
        if (!gotX && !aborted) {
          synchronized (this) {
            wait(1000); // Esperar por notificação ou timeout
          }
        }
      }
      if (aborted)
        return;

      // random(t)
      randomWait();
      if (aborted) {
        resourceX.unlock(this);
        return;
      }

      // Tentar obter lock(Y)
      boolean gotY = false;
      while (!gotY && !aborted) {
        gotY = resourceY.tryLock(this);
        if (!gotY && !aborted) {
          synchronized (this) {
            wait(1000); // Esperar por notificação ou timeout
          }
        }
      }
      if (aborted) {
        resourceX.unlock(this);
        return;
      }

      // random(t) - trabalho crítico
      randomWait();
      if (aborted) {
        resourceY.unlock(this);
        resourceX.unlock(this);
        return;
      }

      // unlock(X)
      resourceX.unlock(this);

      // random(t)
      randomWait();
      if (aborted) {
        resourceY.unlock(this);
        return;
      }

      // unlock(Y)
      resourceY.unlock(this);

      // random(t)
      randomWait();
      if (aborted)
        return;

      // commit(t)
      commit();
    }

    private void randomWait() throws InterruptedException {
      if (!aborted) {
        int waitTime = random.nextInt(1000) + 500; // 500-1500ms
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

      // Remover da fila de espera dos recursos
      resourceX.getQueue().remove(this);
      resourceY.getQueue().remove(this);

      // Reiniciar transação após um delay
      new Thread(() -> {
        try {
          Thread.sleep(random.nextInt(2000) + 1000); // 1-3 segundos
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

  // Detector de deadlock usando grafo de espera
  static class DeadlockDetector implements Runnable {
    private volatile boolean running = true;

    @Override
    public void run() {
      while (running) {
        try {
          Thread.sleep(2000); // Verificar a cada 2 segundos
          detectDeadlock();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }

    private void detectDeadlock() {
      // Construir grafo de espera
      Map<Transaction, Set<Transaction>> waitForGraph = new HashMap<>();

      // Analisar recurso X
      if (resourceX.isLocked()) {
        Transaction holder = resourceX.getCurrentTransaction();
        for (Transaction waiter : resourceX.getQueue()) {
          if (!waiter.isAborted()) {
            waitForGraph.computeIfAbsent(waiter, k -> new HashSet<>()).add(holder);
          }
        }
      }

      // Analisar recurso Y
      if (resourceY.isLocked()) {
        Transaction holder = resourceY.getCurrentTransaction();
        for (Transaction waiter : resourceY.getQueue()) {
          if (!waiter.isAborted()) {
            waitForGraph.computeIfAbsent(waiter, k -> new HashSet<>()).add(holder);
          }
        }
      }

      // Detectar ciclos no grafo
      Set<Transaction> visited = new HashSet<>();
      Set<Transaction> recursionStack = new HashSet<>();

      for (Transaction transaction : waitForGraph.keySet()) {
        if (!visited.contains(transaction)) {
          if (hasCycle(transaction, waitForGraph, visited, recursionStack)) {
            logMessage("DeadlockDetector", "DEADLOCK DETECTADO! Aplicando wait-die...");
            // O wait-die já é aplicado automaticamente no tryLock
            break;
          }
        }
      }
    }

    private boolean hasCycle(Transaction node, Map<Transaction, Set<Transaction>> graph,
        Set<Transaction> visited, Set<Transaction> recursionStack) {
      visited.add(node);
      recursionStack.add(node);

      Set<Transaction> neighbors = graph.get(node);
      if (neighbors != null) {
        for (Transaction neighbor : neighbors) {
          if (!visited.contains(neighbor)) {
            if (hasCycle(neighbor, graph, visited, recursionStack)) {
              return true;
            }
          } else if (recursionStack.contains(neighbor)) {
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