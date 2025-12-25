package de.schildbach.wallet.data.api;

import android.content.Context;

import org.bitcoinj.core.Address;
import org.bitcoinj.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

import de.schildbach.wallet.Constants;

/**
 * Utility class to resolve whether an address belongs to "our" wallet
 * (YOUR ADDRESSES) or is an external address (SENDING ADDRESSES).
 * 
 * An address is "ours" if:
 * 1. It's in the bitcoinj wallet's keychain (isPubKeyHashMine)
 * 2. It exists in OverlayAddressStore (change addresses from outgoing tx)
 * 
 * OVERLAY-SAFE: Only reads from wallet/overlay store, no modifications.
 */
public class AddressRoleResolver {
    private static final Logger log = LoggerFactory.getLogger(AddressRoleResolver.class);

    /**
     * Check if an address belongs to our wallet.
     * 
     * @param context Android context for OverlayAddressStore access
     * @param wallet  Canonical bitcoinj wallet
     * @param address Address string (Base58)
     * @return true if this is one of our addresses
     */
    public static boolean isOurAddress(Context context, Wallet wallet, String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }

        // Check 1: Is it in the bitcoinj wallet's keychain?
        if (wallet != null) {
            try {
                Address addr = Address.fromString(Constants.NETWORK_PARAMETERS, address);
                if (wallet.isPubKeyHashMine(addr.getHash160())) {
                    return true;
                }
            } catch (Exception e) {
                // Invalid address format, continue checking
            }
        }

        // Check 2: Is it in the overlay address store (change addresses)?
        if (context != null) {
            Set<String> overlayAddresses = OverlayAddressStore.getAllAddresses(context);
            if (overlayAddresses.contains(address)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if an address belongs to our wallet (Address object variant).
     */
    public static boolean isOurAddress(Context context, Wallet wallet, Address address) {
        if (address == null) {
            return false;
        }
        return isOurAddress(context, wallet, address.toString());
    }

    /**
     * Get all "our" addresses for exclusion in SendingAddressesFragment.
     * Combines wallet addresses with overlay addresses.
     * 
     * @param context Android context
     * @param wallet  Canonical bitcoinj wallet
     * @return Set of all our address strings
     */
    public static Set<String> getAllOurAddresses(Context context, Wallet wallet) {
        Set<String> addresses = new java.util.HashSet<>();

        // Add overlay addresses (change addresses)
        if (context != null) {
            addresses.addAll(OverlayAddressStore.getAllAddresses(context));
        }

        // Add wallet issued receive addresses
        if (wallet != null) {
            try {
                for (Address addr : wallet.getIssuedReceiveAddresses()) {
                    addresses.add(addr.toString());
                }
                // Also add current receive address
                Address currentReceive = wallet.currentReceiveAddress();
                if (currentReceive != null) {
                    addresses.add(currentReceive.toString());
                }
            } catch (Exception e) {
                log.warn("AddressRoleResolver: failed to get wallet addresses: {}", e.getMessage());
            }
        }

        return addresses;
    }
}
