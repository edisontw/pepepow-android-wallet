package de.schildbach.wallet.data.api;

import org.bitcoinj.core.Block;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.VerificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class HeaderVerifier {

    private static final Logger log = LoggerFactory.getLogger(HeaderVerifier.class);
    private final NetworkParameters params;
    private static final long TARGET_SPACING = 20; // PEPEPOW specific

    public HeaderVerifier(NetworkParameters params) {
        this.params = params;
    }

    public void verifySequentialHeaders(List<HeaderDto> headers) throws VerificationException {
        if (headers == null || headers.isEmpty()) {
            return;
        }

        List<Block> blocks = new ArrayList<>();
        for (HeaderDto dto : headers) {
            blocks.add(dto.toBlock(params));
        }

        for (int i = 0; i < blocks.size(); i++) {
            Block current = blocks.get(i);

            // 1. Verify basic header sanity (timestamp)
            long allowedTime = Utils.currentTimeSeconds() + 2 * 60 * 60;
            if (current.getTimeSeconds() > allowedTime) {
                throw new VerificationException("Block too far in future");
            }

            if (i > 0) {
                Block prev = blocks.get(i - 1);

                // 2. Verify chain continuity (height)
                if (headers.get(i).height != headers.get(i - 1).height + 1) {
                    throw new VerificationException("Non-sequential block height at index " + i);
                }

                // 3. Verify chain continuity (hash)
                if (!current.getPrevBlockHash().equals(prev.getHash())) {
                    throw new VerificationException("Block does not connect to previous block at index " + i + 
                        ". Current prev: " + current.getPrevBlockHash() + ", Actual prev: " + prev.getHash());
                }

                // 4. Verify difficulty (DGW)
                // Need 24 blocks history.
                if (i >= 24) {
                    verifyDifficultyDGW(blocks, i);
                }
            }
        }
    }

    private void verifyDifficultyDGW(List<Block> blocks, int index) throws VerificationException {
        Block nextBlock = blocks.get(index);
        Block storedPrev = blocks.get(index - 1);
        
        long pastBlocks = 24;
        
        BigInteger pastTargetAverage = BigInteger.ZERO;
        
        // Iterate back 24 blocks to calculate average target
        for (int countBlocks = 1; countBlocks <= pastBlocks; countBlocks++) {
            int blockIndex = index - countBlocks;
            Block block = blocks.get(blockIndex);
            BigInteger target = block.getDifficultyTargetAsInteger();
            
            if (countBlocks == 1) {
                pastTargetAverage = target;
            } else {
                pastTargetAverage = pastTargetAverage.multiply(BigInteger.valueOf(countBlocks))
                        .add(target)
                        .divide(BigInteger.valueOf(countBlocks + 1));
            }
        }
        
        BigInteger newTarget = pastTargetAverage;
        
        // cursor is the block 24 blocks ago (index - 24)
        Block cursor = blocks.get(index - 24);
        
        long timespan = storedPrev.getTimeSeconds() - cursor.getTimeSeconds();
        long targetTimespanVal = pastBlocks * TARGET_SPACING;

        if (timespan < targetTimespanVal / 3)
            timespan = targetTimespanVal / 3;
        if (timespan > targetTimespanVal * 3)
            timespan = targetTimespanVal * 3;

        // Retarget
        newTarget = newTarget.multiply(BigInteger.valueOf(timespan));
        newTarget = newTarget.divide(BigInteger.valueOf(targetTimespanVal));
        
        BigInteger powLimit = params.getMaxTarget(); 
        if (newTarget.compareTo(powLimit) > 0) {
            newTarget = powLimit;
        }

        long newTargetCompact = Utils.encodeCompactBits(newTarget);
        long receivedTargetCompact = nextBlock.getDifficultyTarget();

        if (newTargetCompact != receivedTargetCompact) {
             throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                    Long.toHexString(newTargetCompact) + " vs " + Long.toHexString(receivedTargetCompact));
        }
    }
}
