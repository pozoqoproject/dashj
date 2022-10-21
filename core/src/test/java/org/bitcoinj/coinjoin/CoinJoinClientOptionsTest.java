package org.bitcoinj.coinjoin;

import org.bitcoinj.core.Coin;
import org.junit.Test;

import static org.bitcoinj.coinjoin.CoinJoinConstants.COINJOIN_RANDOM_ROUNDS;
import static org.bitcoinj.coinjoin.CoinJoinConstants.DEFAULT_COINJOIN_AMOUNT;
import static org.bitcoinj.coinjoin.CoinJoinConstants.DEFAULT_COINJOIN_DENOMS_GOAL;
import static org.bitcoinj.coinjoin.CoinJoinConstants.DEFAULT_COINJOIN_DENOMS_HARDCAP;
import static org.bitcoinj.coinjoin.CoinJoinConstants.DEFAULT_COINJOIN_MULTISESSION;
import static org.bitcoinj.coinjoin.CoinJoinConstants.DEFAULT_COINJOIN_ROUNDS;
import static org.bitcoinj.coinjoin.CoinJoinConstants.DEFAULT_COINJOIN_SESSIONS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CoinJoinClientOptionsTest {
    @Test
    public void getTest() {
        assertEquals(CoinJoinClientOptions.getSessions(), DEFAULT_COINJOIN_SESSIONS);
        assertEquals(CoinJoinClientOptions.getRounds(), DEFAULT_COINJOIN_ROUNDS);
        assertEquals(CoinJoinClientOptions.getRandomRounds(), COINJOIN_RANDOM_ROUNDS);
        assertEquals(CoinJoinClientOptions.getAmount(), DEFAULT_COINJOIN_AMOUNT);
        assertEquals(CoinJoinClientOptions.getDenomsGoal(), DEFAULT_COINJOIN_DENOMS_GOAL);
        assertEquals(CoinJoinClientOptions.getDenomsHardCap(), DEFAULT_COINJOIN_DENOMS_HARDCAP);

        assertFalse(CoinJoinClientOptions.isEnabled());
        assertEquals(CoinJoinClientOptions.isMultiSessionEnabled(), DEFAULT_COINJOIN_MULTISESSION);

    }

    @Test
    public void setTest() {

        CoinJoinClientOptions.setEnabled(true);
        assertTrue(CoinJoinClientOptions.isEnabled());
        CoinJoinClientOptions.setEnabled(false);
        assertFalse(CoinJoinClientOptions.isEnabled());

        CoinJoinClientOptions.setMultiSessionEnabled(!DEFAULT_COINJOIN_MULTISESSION);
        assertEquals(CoinJoinClientOptions.isMultiSessionEnabled(), !DEFAULT_COINJOIN_MULTISESSION);
        CoinJoinClientOptions.setMultiSessionEnabled(DEFAULT_COINJOIN_MULTISESSION);
        assertEquals(CoinJoinClientOptions.isMultiSessionEnabled(), DEFAULT_COINJOIN_MULTISESSION);

        CoinJoinClientOptions.setRounds(DEFAULT_COINJOIN_ROUNDS + 10);
        assertEquals(CoinJoinClientOptions.getRounds(), DEFAULT_COINJOIN_ROUNDS + 10);
        CoinJoinClientOptions.setAmount(DEFAULT_COINJOIN_AMOUNT.add(Coin.FIFTY_COINS));
        assertEquals(CoinJoinClientOptions.getAmount(), DEFAULT_COINJOIN_AMOUNT.add(Coin.FIFTY_COINS));
    }
}
