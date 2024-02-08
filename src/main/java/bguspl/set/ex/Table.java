package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
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

    /**
     * Mapping between a slot and the player token placed on it.
     * This is a readonly copy, trying to change the map will result UnsupportedOperationException.
     * Since we're using vectors, any access and modification to the values in map is thread safe.
     */
    protected final Map<Integer, List<Integer>> slotsToPlayer;

    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {
        HashMap<Integer, Vector<Integer>> slotsToPlayer = new HashMap<>();
        for (int i = 0; i < slotToCard.length; i++) {
            slotsToPlayer.put(i, new Vector<>()); // Vectors are thread safe, do not change that.
        }
        this.slotsToPlayer = Collections.unmodifiableMap(slotsToPlayer);

        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
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
        List<Integer> deck = Arrays.stream(slotToCard)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set)
                    .mapToObj(card -> cardToSlot[card])
                    .sorted()
                    .collect(Collectors.toList());
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

    // Since the dealer thread is the only one allowed to call the next 2 methods, we don't need to synchronize them.

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) { }

        cardToSlot[card] = slot;
        slotToCard[slot] = card;
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     *
     * @pre  - slotToCard[slot] & cardToSlot[slotToCard[slot]] are not null.
     * @post - slotToCard[slot] & cardToSlot[slotToCard[slot]] are null.
     */
    public void removeCard(int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        slotsToPlayer.get(slot).clear(); // This is ok since we're using read-only map with vectors.
        cardToSlot[slotToCard[slot]] = null;
        slotToCard[slot] = null;
    }

    /**
     * Places a player token on a grid slot.
     * @param player - the id of player the token belongs to.
     * @param slot   - the slot on which to place the token. 0 indexed.
     */
    public void placeToken(int player, int slot) {
        slotsToPlayer.get(slot).add(player);
    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token. 0 indexed.
     * @return       - true iff a token was successfully removed.
     */
    public boolean removeToken(int player, int slot) {
        return slotsToPlayer.containsKey(slot) && slotsToPlayer.get(slot).remove(Integer.valueOf(player));
    }
}
