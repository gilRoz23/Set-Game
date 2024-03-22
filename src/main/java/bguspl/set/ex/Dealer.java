package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck; // NOT IN USE! THE DECK IS IN THE TABLE

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    /**
     * NEW VARIABLES
     **/
    private int sleepTime = 0;
    private List<Integer> setFromQueue;
    public static final int SET_SIZE = 3;
    public static final int SECOND = 1000;
    public static final int TEN_MILLIS = 10;
    public static final int IRRELEVANT_SLOT = -1;


    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        this.setFromQueue = new ArrayList<>();
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " running.");
        //init players threads
        for (int i = 0; i < players.length; i++) {
            Thread ithPlayerThread = new Thread(players[i]);
            ithPlayerThread.start();
        }

        while (!shouldFinish()) {
            placeCardsOnEmptySlotsAndResetTimer();
            nonResetTimeUpdatingAndSetsChecking();
            boolean isReset = true;
            updateTimerDisplay(isReset); //reset round
            removeAllSlotsWithCardFromTable();

        }
        announceWinners();
        terminate();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void nonResetTimeUpdatingAndSetsChecking() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepAndCheckForSets();
            boolean isReset = false;
            updateTimerDisplay(isReset);

        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {

        while (!table.playersOrder.isEmpty()){
            Player player = players[table.playersOrder.pop()];
            player.terminate();


        }
        terminate = true; // close the dealer thread too

    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(table.deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable(List<Integer> slotsToRemove, boolean removeForever) { // remove for a valid set
        while (!slotsToRemove.isEmpty()) {
            int randomIndex = (int) (Math.random() * slotsToRemove.size());
            int slotToRemove = slotsToRemove.get(randomIndex);
            table.removeCard(slotToRemove, removeForever); // remove the card on the slot
            slotsToRemove.remove(randomIndex); // remove the slot from slots to remove.

            // + remove the bad slots from the queues of the players
            for (Player curr_player : players) {
                while (curr_player.playerSlotsRequestsQ.contains(slotToRemove)) {
                    curr_player.playerSlotsRequestsQ.remove(slotToRemove); // if the slot is in the queue of the player - remove it.
                }
                if (curr_player.slotFromQueue == slotToRemove) { // check if the player after stopping is holding a slot that won't be relevant.
                    curr_player.slotFromQueue = IRRELEVANT_SLOT;
                }

                table.removeToken(curr_player.id, slotToRemove); // remove the tokens if they are on the slots
            }


        }
    }


    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnEmptySlotsAndResetTimer() {


        while (!table.emptySlots.isEmpty() && !table.deck.isEmpty()) { //checking if are there any slots that need to be fill, and any cards in deck that can fill
            int randomSlotIndex = (int) (Math.random() * table.emptySlots.size());
            int randomCardIndex = (int) (Math.random() * table.deck.size());
            // update the arrays
            int card = table.deck.remove(randomCardIndex); // saves and removes the card from the deck
            int slot = table.emptySlots.remove(randomSlotIndex); // saves and removes the slot from the empty slots.
            // place the card on table
            table.placeCard(card, slot);

        }
        if ((!shouldFinish()) & env.util.findSets(table.tableCards, 1).isEmpty()) {
            removeAllSlotsWithCardFromTable();
            placeCardsOnEmptySlotsAndResetTimer();
        }
        //after placing cards, refresh the reshuffle time, and start from 60
        updateTimerDisplay(true);

        // after finishing placing cards on the table - make all the players continue.

        synchronized (table.allPlayersLock) {
            table.allPlayersLock.notifyAll();
        }
        if (shouldFinish()){
            removeAllSlotsWithCardFromTable();
            terminate = true;
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepAndCheckForSets() {

        try {

            Integer playerId;
            playerId = table.setsForDealer.poll(sleepTime, TimeUnit.MILLISECONDS); //sleep a millisecond and poll

            if (playerId != null) { // woke up because of a set
                setFromQueue = table.tokensOnTable.get(playerId);
                if (setFromQueue.size() == SET_SIZE) { // size of set is ok. (no token was removed from the set)
                    int[] setAsCards = new int[SET_SIZE];
                    for (int i = 0; i < SET_SIZE; i++) {
                        setAsCards[i] = table.slotToCard[setFromQueue.get(i)];
                    }


                    boolean setIsValid = env.util.testSet(setAsCards);

                    if (setIsValid) { // set is valid

                        synchronized (table) {
                            table.areAllCanRecieveKey = false;
                            Player player = players[playerId];
                            // give him a point
                            player.point();

                            //change the variable of player to 1;
                            player.penaltyTime = (int) env.config.pointFreezeMillis;

                            //remove cards
                            removeCardsFromTable(setFromQueue, true);

                            //place 3 new cards (if there are enough) - the reset of time will be inside this method
                            placeCardsOnEmptySlotsAndResetTimer();

                            // + player gets out of wait and checks the one sec freeze
                            // every one can receive keys
                            table.areAllCanRecieveKey = true;

                        }

                    } else { //set is not valid
                        // penalize the player
                        players[playerId].penaltyTime = (int) env.config.penaltyFreezeMillis;
                        while(!table.tokensOnTable.get(playerId).isEmpty()){
                            table.removeToken(playerId, table.tokensOnTable.get(playerId).get(0));
                        }

                    }
                }

                //dealer finished with the players set -> wake him up.
                synchronized (players[playerId]) {
                    players[playerId].notifyAll();
                }

            }
        } catch (InterruptedException ignored) {
        }
        ;

    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean doReset) {
        if (doReset) {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            sleepTime = SECOND ;
            env.ui.setCountdown(env.config.turnTimeoutMillis, false);
        } else {
            if (reshuffleTime - System.currentTimeMillis() < env.config.turnTimeoutWarningMillis) {
                sleepTime = TEN_MILLIS;
                env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), true);

            } else {
                env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);
            }

        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllSlotsWithCardFromTable() {
        List<Integer> slotsToRemove = new ArrayList<>(env.config.tableSize - table.emptySlots.size());
        for (int i = 0; i < env.config.tableSize; i++) {
            if (!table.emptySlots.contains(i)) { // if the slot is not empty right now - add it to the slots to remove list.
                slotsToRemove.add(i);
            }
        }

        removeCardsFromTable(slotsToRemove, false);
        setFromQueue.clear();
    }

    public void test_RemoveAllCardsOnTable(){
        removeAllSlotsWithCardFromTable();
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {

        int bestScore = 0;
        int count = 0;
        // check how many winners there are
        for (Player player : players) {
            if (player.score() > bestScore) {
                bestScore = player.score();
                count = 1;
            } else if (player.score() == bestScore) {
                count += 1;
            }
            System.out.println("Player "+player.id+" has "+player.score()+" points");
        }
        // create an array of that size and add the winners' ids.
        int[] winnersIds = new int[count];
        for (Player player : players) {
            if (player.score() == bestScore) {
                winnersIds[winnersIds.length - 1] = player.id;
            }
        }

        env.ui.announceWinner(winnersIds);


    }

    public void callAnnounceWinners(){
        announceWinners();
    }
}