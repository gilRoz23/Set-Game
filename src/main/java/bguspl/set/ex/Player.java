package bguspl.set.ex;

import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    protected Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /**
     * NEW FIELDS
     **/
    protected BlockingQueue<Integer> playerQueue; // thread-safe queue
    protected Integer slotFromQueue;
    protected int penaltyTime = 0;
    protected boolean playerCanRecieveKey;
    public static final int SECOND_MILLIS = 1000;


    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.slotFromQueue = Dealer.IRRELEVANT_SLOT;
        this.playerCanRecieveKey = true;
        this.playerQueue = new LinkedBlockingQueue<>(3);
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        table.playersStartLock.lock();
        playerThread = Thread.currentThread();
        table.playersOrder.push(id);

        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + "starting.");
        table.playersStartLock.unlock();

        if (!human) createArtificialIntelligence();

        synchronized (table.allPlayersLock) { // when player thread starts, he waits until dealer finishes placing card and wakes him up
            try {
                table.allCanRecieveKey = false;
                table.allPlayersLock.wait();
            } catch (InterruptedException ignored) {
            } finally {
                table.allCanRecieveKey = true;
            }
        }

        // ** main loop for the thread **
        while (!terminate) {

            try {
                slotFromQueue = playerQueue.take(); // take out the slot
                synchronized (this) {
                    boolean shouldSleep = false;
                    synchronized (table) {
                        shouldSleep = table.placeToken(id, slotFromQueue); // send to the table
                    }
                    if (shouldSleep) { // should sleep if it sent its tokens set to the queue.
                        try {
                            playerCanRecieveKey = false;
                            wait();
                        } catch (InterruptedException ignored) {
                        }
                    }
                    penalty(); // check if should get penalty.
                    playerCanRecieveKey = true;
                    penaltyTime = 0; // reset the penalty time.

                }

            } catch (InterruptedException ignored) {
            }

        }

        if (!human) try {
            aiThread.join();
        } catch (InterruptedException ignored) {
        }

        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");

    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                if (env.config.PauseAITime>0) {
                    try {
                        Thread.sleep(env.config.PauseAITime);
                    } catch (InterruptedException e) {};
                }
                int randomSlot = (int) ((Math.random() * (env.config.tableSize)));

                keyPressed(randomSlot);


            }

            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate = true; // indicate player needs to stop
        try {
            if (!human) {
                aiThread.interrupt();
                aiThread.join();

            }
            playerThread.interrupt();
            playerThread.join(); // wait for the player to stop
        } catch (InterruptedException ignored) {
        }

    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        // add the slot to queue
        if (playerCanRecieveKey & table.allCanRecieveKey & !table.emptySlots.contains(slot)) {
            try {
                    playerQueue.put(slot);

            } catch (InterruptedException ignored) {
            }
        }

    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        // TODO implement

        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        // check how long is the penalty
        try {
            for (int i = penaltyTime; i > 0; i = i - SECOND_MILLIS) {
                env.ui.setFreeze(id, i);
                Thread.sleep(SECOND_MILLIS);
            }

            env.ui.setFreeze(id, 0);
        } catch (InterruptedException ignored) {
        }

    }

    public int score() {
        return score;
    }
}