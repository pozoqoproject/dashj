/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.wallet;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.*;
import org.bitcoinj.script.Script;
import org.bitcoinj.utils.ListenerRegistration;
import org.bitcoinj.wallet.listeners.KeyChainEventListener;
import org.bouncycastle.crypto.params.KeyParameter;

import javax.annotation.Nullable;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class AuthenticationKeyChain extends ExternalKeyChain {

    public enum KeyChainType {
        BLOCKCHAIN_IDENTITY,
        BLOCKCHAIN_IDENTITY_FUNDING,
        MASTERNODE_HOLDINGS,
        MASTERNODE_OWNER,
        MASTERNODE_OPERATOR,
        MASTERNODE_VOTING,
        BLOCKCHAIN_IDENTITY_TOPUP,
        INVITATION_FUNDING,
        INVALID_KEY_CHAIN
    }
    KeyChainType type;
    boolean hardenedChildren;
    int currentIndex;
    int issuedKeys;

    public static class Builder<T extends Builder<T>> {
        protected SecureRandom random;
        protected int bits = DeterministicSeed.DEFAULT_SEED_ENTROPY_BITS;
        protected String passphrase;
        protected long creationTimeSecs = 0;
        protected byte[] entropy;
        protected DeterministicSeed seed;
        protected boolean isFollowing = false;
        protected DeterministicKey spendingKey = null;
        protected ImmutableList<ChildNumber> accountPath = null;
        protected KeyChainType type = null;
        protected boolean hardenedChildren = false;

        protected Builder() {
        }

        @SuppressWarnings("unchecked")
        protected T self() {
            return (T)this;
        }

        /**
         * Creates a deterministic key chain starting from the given seed. All keys yielded by this chain will be the same
         * if the starting seed is the same.
         */
        public T seed(DeterministicSeed seed) {
            this.seed = seed;
            return self();
        }

        /**
         * Generates a new key chain with entropy selected randomly from the given {@link SecureRandom}
         * object and of the requested size in bits.  The derived seed is further protected with a user selected passphrase
         * (see BIP 39).
         * @param random the random number generator - use new SecureRandom().
         * @param bits The number of bits of entropy to use when generating entropy.  Either 128 (default), 192 or 256.
         */
        public T random(SecureRandom random, int bits) {
            this.random = random;
            this.bits = bits;
            return self();
        }

        /**
         * Generates a new key chain with 128 bits of entropy selected randomly from the given {@link SecureRandom}
         * object.  The derived seed is further protected with a user selected passphrase
         * (see BIP 39).
         * @param random the random number generator - use new SecureRandom().
         */
        public T random(SecureRandom random) {
            this.random = random;
            return self();
        }

        /**
         * Creates a key chain that can spend from the given account key.
         */
        public T spend(DeterministicKey accountKey) {
            checkState(accountPath == null, "either spend or accountPath");
            this.spendingKey = accountKey;
            this.isFollowing = false;
            return self();
        }

        /** The passphrase to use with the generated mnemonic, or null if you would like to use the default empty string. Currently must be the empty string. */
        public T passphrase(String passphrase) {
            // FIXME support non-empty passphrase
            this.passphrase = passphrase;
            return self();
        }

        /**
         * Use an account path other than the default {@link DeterministicKeyChain#ACCOUNT_ZERO_PATH}.
         */
        public T accountPath(ImmutableList<ChildNumber> accountPath) {
            this.accountPath = checkNotNull(accountPath);
            return self();
        }

        public T type(KeyChainType type) {
            this.type = type;
            return self();
        }

        public T createHardenedChildren(boolean hardedChildren) {
            this.hardenedChildren = hardedChildren;
            return self();
        }

        public AuthenticationKeyChain build() {
            checkState(passphrase == null || seed == null, "Passphrase must not be specified with seed");

            if (accountPath == null)
                accountPath = ACCOUNT_ZERO_PATH;
            if (type == null)
                type = KeyChainType.INVALID_KEY_CHAIN;

            if (random != null)
                // Default passphrase to "" if not specified
                return new AuthenticationKeyChain(new DeterministicSeed(random, bits, getPassphrase()), null,
                        accountPath, type, hardenedChildren);
            else if (entropy != null)
                return new AuthenticationKeyChain(new DeterministicSeed(entropy, getPassphrase(), creationTimeSecs),
                        null, accountPath, type, hardenedChildren);
            else if (seed != null)
                return new AuthenticationKeyChain(seed, null, accountPath, type, hardenedChildren);
            else if (spendingKey != null)
                return new AuthenticationKeyChain(spendingKey, type, hardenedChildren);
            else
                throw new IllegalStateException();
        }

        protected String getPassphrase() {
            return passphrase != null ? passphrase : DEFAULT_PASSPHRASE_FOR_MNEMONIC;
        }
    }



    public AuthenticationKeyChain(DeterministicSeed seed, ImmutableList<ChildNumber> path) {
        super(seed, null, path);
        setLookaheadSize(5);
    }

    public AuthenticationKeyChain(DeterministicSeed seed, KeyCrypter keyCrypter, ImmutableList<ChildNumber> path) {
        super(seed, keyCrypter, path);
        setLookaheadSize(5);
    }

    public AuthenticationKeyChain(DeterministicKey key) {
        super(key, false);
    }

    public AuthenticationKeyChain(DeterministicSeed seed, ImmutableList<ChildNumber> path, KeyChainType type, boolean hardenedChildren) {
        this(seed, path);
        this.type = type;
        this.hardenedChildren = hardenedChildren;
    }

    public AuthenticationKeyChain(DeterministicSeed seed, KeyCrypter keyCrypter, ImmutableList<ChildNumber> path, KeyChainType type, boolean hardenedChildren) {
        this(seed, keyCrypter, path);
        this.type = type;
        this.hardenedChildren = hardenedChildren;
    }

    public AuthenticationKeyChain(DeterministicKey key, KeyChainType type, boolean hardenedChildren) {
        this(key);
        this.type = type;
        this.hardenedChildren = hardenedChildren;
    }

    /**
     * For use in encryption when {@link #toEncrypted(KeyCrypter, KeyParameter)} is called, so that
     * subclasses can override that method and create an instance of the right class.
     *
     * See also {@link #makeKeyChainFromSeed(DeterministicSeed, ImmutableList, Script.ScriptType)}
     */
    protected AuthenticationKeyChain(KeyCrypter crypter, KeyParameter aesKey, AuthenticationKeyChain chain) {
        super(crypter, aesKey, chain);
        this.type = chain.type;
    }

    public static Builder<?> authenticationBuilder() {
        return new Builder();
    }

    /**
     * Sets the KeyChainType of this AuthenticationKeyChain.  Used by Wallet when loading from a protobuf
     * @param type the type of authentication key chain
     */
    /* package */
    void setType(KeyChainType type) {
        this.type = type;
    }

    @Override
    public DeterministicKey getKey(KeyPurpose purpose) {
        return getKeys(purpose, 1).get(0);
    }

    @Override
    public List<DeterministicKey> getKeys(KeyPurpose purpose, int numberOfKeys) {
        checkArgument(numberOfKeys > 0);
        lock.lock();
        try {
            DeterministicKey parentKey;
            int index;
            switch (purpose) {
                case AUTHENTICATION:
                    issuedKeys += numberOfKeys;
                    index = issuedKeys;
                    parentKey = getKeyByPath(getAccountPath());
                    break;
                default:
                    throw new UnsupportedOperationException();
            }

            //TODO: do we need to look ahead here, even for one key?  Does anything get saved?
            /** Optimization: see {@link DeterministicKeyChain.getKeys(org.bitcoinj.wallet.KeyChain.KeyPurpose, int)} */

            List<DeterministicKey> lookahead = maybeLookAhead(parentKey, index, 0, 0);
            basicKeyChain.importKeys(lookahead);
            List<DeterministicKey> keys = new ArrayList<DeterministicKey>(numberOfKeys);
            for (int i = 0; i < numberOfKeys; i++) {
                ImmutableList<ChildNumber> path = HDUtils.append(parentKey.getPath(), new ChildNumber(index - numberOfKeys + i, false));
                DeterministicKey k = hierarchy.get(path, false, false);
                checkForBitFlip(k);
                keys.add(k);
            }
            return keys;
        } finally {
            lock.unlock();
        }
    }

    public DeterministicKey getKey(int index, boolean isHardened) {
        return getKeyByPath(new ImmutableList.Builder().addAll(getAccountPath()).addAll(ImmutableList.of(new ChildNumber(index, isHardened))).build(), true);
    }

    public DeterministicKey getKey(int index) {
        return getKeyByPath(new ImmutableList.Builder().addAll(getAccountPath()).addAll(ImmutableList.of(new ChildNumber(index, hardenedChildren))).build(), true);
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public DeterministicKey freshAuthenticationKey(boolean isHardened) {
        return getKey(KeyPurpose.AUTHENTICATION);
    }

    public DeterministicKey currentAuthenticationKey(boolean isHardened) {
        return getKey(currentIndex, isHardened);
    }

    public DeterministicKey getKeyByPubKeyHash(byte [] hash160) {
        Preconditions.checkState(hash160.length == 20);
        return (DeterministicKey)basicKeyChain.findKeyFromPubHash(hash160);
    }

    @Override
    public String toString(boolean includeLookahead, boolean includePrivateKeys, @Nullable KeyParameter aesKey, NetworkParameters params) {
        return "Authentication Key Chain: " + (type != null ? type.toString() : "unknown") + "\n" +
            super.toString(includeLookahead, includePrivateKeys, aesKey, params);
    }

    @Override
    public AuthenticationKeyChain toEncrypted(KeyCrypter keyCrypter, KeyParameter aesKey) {
        return new AuthenticationKeyChain(keyCrypter, aesKey, this);
    }

    @Override
    public AuthenticationKeyChain toDecrypted(KeyParameter aesKey) {
        checkState(getKeyCrypter() != null, "Key chain not encrypted");
        checkState(getSeed() != null, "Can't decrypt a watching chain");
        checkState(getSeed().isEncrypted());
        String passphrase = DEFAULT_PASSPHRASE_FOR_MNEMONIC; // FIXME allow non-empty passphrase
        DeterministicSeed decSeed = getSeed().decrypt(getKeyCrypter(), passphrase, aesKey);
        AuthenticationKeyChain chain = new AuthenticationKeyChain(decSeed, getAccountPath(), type, hardenedChildren);
        // Now double check that the keys match to catch the case where the key is wrong but padding didn't catch it.
        if (!chain.getWatchingKey().getPubKeyPoint().equals(getWatchingKey().getPubKeyPoint()))
            throw new KeyCrypterException.PublicPrivateMismatch("Provided AES key is wrong");
        chain.lookaheadSize = lookaheadSize;
        // Now copy the (pubkey only) leaf keys across to avoid rederiving them. The private key bytes are missing
        // anyway so there's nothing to decrypt.
        for (ECKey eckey : basicKeyChain.getKeys()) {
            DeterministicKey key = (DeterministicKey) eckey;
            if (key.getPath().size() != getAccountPath().size() + 2) continue; // Not a leaf key.
            checkState(key.isEncrypted());
            DeterministicKey parent = chain.hierarchy.get(checkNotNull(key.getParent()).getPath(), false, false);
            // Clone the key to the new decrypted hierarchy.
            key = new DeterministicKey(key.dropPrivateBytes(), parent);
            chain.hierarchy.putKey(key);
            chain.basicKeyChain.importKey(key);
        }
        chain.issuedExternalKeys = issuedExternalKeys;
        chain.issuedInternalKeys = issuedInternalKeys;
        for (ListenerRegistration<KeyChainEventListener> listener : basicKeyChain.getListeners()) {
            chain.basicKeyChain.addEventListener(listener);
        }
        return chain;
    }

    public KeyChainType getType() {
        return type;
    }

    public void setHardenedChildren(boolean hardenedChildren) {
        this.hardenedChildren = hardenedChildren;
    }
}
