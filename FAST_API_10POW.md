✅ 1. Detailed Developer Version — FAST_API_10POW: Principles, SPV Comparison, Security Model
1. Overview

FAST_API_10POW is a hybrid fast-sync mechanism for the PepePoW Android Wallet that accelerates initial synchronization while preserving essential security guarantees.
The design combines:

API-based header sourcing

Fixed-window PoW spot-verification

Difficulty verification

Header-only P2P sync after bootstrap

Its purpose is to eliminate full chain replay and remove the need for static checkpoints, while still detecting forged chains or malicious API responses.

2. Architecture & Workflow
2.1 Step-by-Step Process

API Height Discovery
The wallet queries the explorer API for:

current chain tip

block hash of the tip

difficulty & chainwork

Header Window Extraction (Tip−1000)
The wallet downloads the most recent 1000 block headers from the API.

Window Validation
The client performs:

structural validation for each header

parent/child linkage checks

difficulty target validation per header

cumulative chainwork reconstruction

PoW Spot-Verification (10 Random Blocks)
The wallet chooses 10 random blocks within the 1000-block window and performs full PoW hash validation.

These 10 blocks act as statistical anchors confirming the authenticity of the whole window.

Header Commit to SPV BlockStore
The last header in the window becomes the chain head of the SPVBlockStore.

Transition to Standard SPV
After bootstrap:

full PoW remains disabled

difficulty verification stays enabled

header-only P2P sync continues normally

This turns “1000 validated headers” into a trusted anchor for fast SPV.

3. Comparison With Traditional SPV Clients
Feature	Traditional Bitcoin SPV	FAST_API_10POW
Header Source	P2P only	API snapshot, then P2P
Initial Sync Speed	Slow, linear replay	Instant bootstrap
PoW Verification	Per block	10-block random spot-check
Difficulty Verification	Always	Always
Trust Model	Pure PoW trust	API-assisted + PoW safety net
Checkpoints	Often required	None
Advantages over classic SPV

No need to re-verify millions of PoW blocks

No reliance on fixed checkpoints that become outdated

Detects API tampering via PoW randomness

Still compatible with BitcoinJ’s SPV consensus rules

4. Security Model
4.1 Attack Scenarios

The main threat is an attacker controlling or compromising the API server and providing a fake 1000-header chain.

To succeed, the attacker must:

(A) Forge valid difficulty-adjusted headers

Headers must:

meet difficulty bits

connect parent→child

build coherent chainwork

(B) Pass 10 PoW spot-verification checks

Each randomly selected header must contain:

a valid nonce

a valid block hash below target

valid merkle root structure

Forging one PoW block is computationally expensive; forging 10 is unrealistic.

4.2 Probability of Success

If faking a PoW block has probability:

P(forge_PoW) = extremely low (near zero)


then forging 10:

P(forge_10_PoW) = (P(forge_PoW))^10


This is astronomically impossible for any realistic adversary.

4.3 Why 1000 Headers?

A 1000-block window ensures:

sufficient difficulty variation

meaningful chainwork reconstruction

low correlation for random PoW selection

comparable length to Bitcoin’s SPV checkpoints

5. When This Is Safe

FAST_API_10POW is secure when:

explorer API is honest or

attacker cannot forge 10 valid PoW blocks

This aligns closely with the security model of:

Electrum servers

Neutrino compact block filters

Ethereum fast-sync trusted checkpoints

Geth's "light client sync" pre-snapshot verification

6. When to Use FULL_SPV Instead

Developers or auditors should use FULL_SPV mode when:

validating entire historical PoW chain

building archival nodes

performing forensic analysis

testing consensus rule changes

FAST_API_10POW is intended for end-user wallets, not validators.

🔚 End of Detailed Developer Version