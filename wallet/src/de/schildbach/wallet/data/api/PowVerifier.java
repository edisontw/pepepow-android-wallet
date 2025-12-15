package de.schildbach.wallet.data.api;

import org.bitcoinj.core.Block;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.VerificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class PowVerifier {

    private static final Logger log = LoggerFactory.getLogger(PowVerifier.class);
    private final NetworkParameters params;

    public PowVerifier(NetworkParameters params) {
        this.params = params;
    }

    public void verifyBlocks(List<Block> blocks) throws VerificationException {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        log.info("Verifying PoW for {} blocks", blocks.size());
        for (Block block : blocks) {
            block.verifyHeader();
        }
        log.info("PoW verification passed for all {} blocks", blocks.size());
    }

    public void verifyPow(List<HeaderDto> headers) throws VerificationException {
        if (headers == null || headers.isEmpty()) {
            return;
        }

        int sampleSize = Math.min(headers.size(), 10);
        List<HeaderDto> samples = new ArrayList<>(headers);
        
        // If we have more than 10, pick 10 random ones. 
        // If 10 or fewer, verify all of them.
        if (headers.size() > 10) {
            Collections.shuffle(samples, new Random());
            samples = samples.subList(0, 10);
        }

        log.info("Verifying PoW for {} sampled headers", samples.size());

        for (HeaderDto dto : samples) {
            Block block = dto.toBlock(params);
            // Use bitcoinj internal verification to keep difficulty validation intact.
            block.verifyHeader();
        }
        log.info("PoW verification passed for all samples");
    }
}
