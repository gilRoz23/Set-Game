package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)

    protected Vector<Integer> deck;

    protected Vector<Integer> emptySlots;

    protected List<List<Integer>> tokensOnTable;

    protected Object allPlayersLock = new Object(); // lock for all players

    protected boolean areAllCanRecieveKey = true;

    protected BlockingQueue<Integer> setsForDealer;

    protected Stack<Integer> playersOrder;

    protected ReentrantLock playersStartLock = new ReentrantLock(true);

    protected List<Integer> tableCards;

    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {

        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        this.deck = new Vector<Integer>();
        for (int i = 0; i < env.config.deckSize; i++) { // creating the deck
            deck.add(i);
        }
        tokensOnTable = new ArrayList<>(env.config.players); // init tokens nested list
        for (int i = 0; i < env.config.players; i++) {
            tokensOnTable.add(new ArrayList<>());
        }
//        initiate which slots in table are empty
        emptySlots = new Vector<Integer>();
        for (int i = 0; i < slotToCard.length; i++) {
            emptySlots.add(i);
        }
        setsForDealer = new LinkedBlockingQueue<>(env.config.players);
        //init players threads stack
        playersOrder = new Stack<>();

        areAllCanRecieveKey = true;
        tableCards = new LinkedList<Integer>();
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     *
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     * @post - the card placed is on the table, in the assigned slot.
     */
    public synchronized void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {
        }

        cardToSlot[card] = slot;
        slotToCard[slot] = card;
        Integer integerCard = card;
        tableCards.add(integerCard);

        env.ui.placeCard(card, slot);
    }

    /**
     * Removes a card from a grid slot on the table.
     *
     * @param slot - the slot from which to remove the card.
     */
    public void removeCard(int slot, boolean removeForever) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {
        }

        emptySlots.add(slot); // add the slots that will be removed to the empty slots
        Integer slotCard = slotToCard[slot];
        tableCards.remove(slotCard);

        if (removeForever)
            cardToSlot[slotToCard[slot]] = Dealer.IRRELEVANT_SLOT; // make the card not available anymore.
        else {
            deck.add(slotToCard[slot]);
            cardToSlot[slotToCard[slot]] = null; // make the card available again
        }
        slotToCard[slot] = null; // the slot is empty now.

        // ui
        env.ui.removeCard(slot);
    }

    /**
     * Places a player token on a grid slot.
     *
     * @param playerId - the player the token belongs to.
     * @param slot     - the slot on which to place the token.
     */
    public synchronized boolean placeTokenReturnIsPlayerShouldSleep(int playerId, int slot) { // changed to boolean

        if (slot == Dealer.IRRELEVANT_SLOT | emptySlots.contains(slot)) { // if the slot he holds is not relevant anymore
            return false;
        } else {
            if (tokensOnTable.get(playerId).contains(slot)) { // there's a token on this slot --> needs to be removed
                removeToken(playerId, slot);
                return false;

            } else { // I need to place the token

                if (tokensOnTable.get(playerId).size() >= Dealer.SET_SIZE) // if i already have 3, and want to add another token - dont do anything.
                    return false;

                tokensOnTable.get(playerId).add(slot); // add the slot to player's tokens
                env.ui.placeToken(playerId, slot);

                if (tokensOnTable.get(playerId).size() < Dealer.SET_SIZE) { // case where there are 1 or two tokens now.
                    return false;
                }
                // add the set to the dealer's queue
                if (tokensOnTable.get(playerId).size() == Dealer.SET_SIZE) {
                    env.logger.log(Level.WARNING, "Player " + playerId + " claimed a set.");
                    setsForDealer.add(playerId);

                    return true;
                }
            }


        }
        return false;
    }

    /**
     * Removes a token of a player from a grid slot.
     *
     * @param playerId - the player the token belongs to.
     * @param slot     - the slot from which to remove the token.
     * @return - true iff a token was successfully removed.
     */
    public boolean removeToken(int playerId, int slot) {
        // if there is a token in the player's tokens - remove it
        Integer intSlot = slot;
        env.ui.removeToken(playerId, slot);
        return tokensOnTable.get(playerId).remove(intSlot); // returns true iff removed successfully

    }
}