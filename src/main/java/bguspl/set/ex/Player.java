package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

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
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /**
     * The state determines what's the player's state.
     */
    private enum PlayerState {
        PLAY,
        PENALTY_FREEZE,
        POINT_FREEZE
    }
    private PlayerState state;

    /**
     * The queue of key presses (used by the AI player).
     */
    //private final BlockingQueue<Integer> keyPresses;
    // TODO have some messaging to the player if it's tokes we're removed by the dealer.
    // FIXME - important

    /**
     * The maximum number of key presses that can be processed at the time in the queue.
     * This was not mentioned as part of the configuration field that we need to support for the bonus.
     */
    private static final int MAX_KEY_PRESSES = 3;

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
        this.state = null;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        // Set the state to play, now we're not blocking this thread.
        this.state = PlayerState.PLAY;

        while (!terminate) {
            // TODO implement main player loop
            switch(state) {
                case PLAY:
                    // TAKE FROM QUEUE AND PERFORM GAME LOGIC WITH DEALER

                    break;
                case POINT_FREEZE:
                    //CLEAR QUEUE
                    try {
                        playerThread.sleep(env.config.pointFreezeMillis);
                    } catch (InterruptedException e) {
                        env.logger.warning(playerThread.getName() + " was interrupted, during point freeze.");
                    }
                    break;
                case PENALTY_FREEZE:
                    // DO NOTHING
                    try {
                        playerThread.sleep(env.config.penaltyFreezeMillis);
                    } catch (InterruptedException e) {
                        env.logger.warning(playerThread.getName() + " was interrupted, during penalty freeze.");
                    }
                    break;
                default:
                    env.logger.warning(
                            "Player " + playerThread.getName() + "  entered illegal state");
            }

        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            Random random = new Random();
            int maxSlot = env.config.columns * env.config.rows;

            while (!terminate) {
                // todo the rest
                keyPressed(random.nextInt(maxSlot));

                // WHY DID WE NEED THAT?
//                try {
//                    keyPressed(random.nextInt(maxSlot));
//                    synchronized (this) { wait(); }
//                } catch (InterruptedException ignored) { }
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        // TODO implement
        // Join with main thread
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        // TODO implement
        // FIXME - this needs to be changed so the operation in the table happens post freeze delay
        if(!table.removeToken(id, slot)) {
            try {
                table.placeToken(id, slot);
                keyPresses.put(slot);
            } catch (IllegalStateException e) {
                env.logger.warning(
                        "Player " + id + " tried to place token on empty slot, most likely card was removed");
            } catch (InterruptedException  e) {
                env.logger.warning(
                        "Player " + id + " was interrupted while trying to place token");
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
        notifyAll();
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        // TODO implement

        notifyAll();
    }

    public int score() {
        return score;
    }
}
