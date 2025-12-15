FAST_API_10POW code map
-----------------------

Selection and bootstrap entry
- `src/de/schildbach/wallet/service/BlockchainServiceImpl.initSyncPipeline()` reads `Configuration.getSyncMode()` and sets `selectedSyncMode`, toggling `AbstractBlockChain.FAST_API_10POW_ENABLED` / `API_MODE_NO_HISTORY`.
- API modes spawn a dedicated `bootstrapThread` that calls `runBootstrapIfNeeded()` before SPV init.

Header snapshot flow
- `runBootstrapIfNeeded()` obtains the shared `ApiPowBootstrapper` (`WalletApplication.getBootstrapper()`).
- `ApiPowBootstrapper.runBootstrapIfNeeded()` fetches the explorer tip via `ApiHeaderClient`, persists a single tip header into the `SPVBlockStore` via `persistSingleHeader()`, and marks it as chain head. It also records API tip metadata in `Configuration` for UI.
- `BlockchainServiceImpl.alignBlockStoreHeadWithBootstrapResult()` re-applies the stored API tip to the block store chain head after bootstrap to keep SPV aligned.

SPV creation/start
- `BlockchainServiceImpl.initializeSpv()` waits on the bootstrap latch, opens the `SPVBlockStore`, sets `AbstractBlockChain.API_SNAPSHOT_TIP_HEIGHT/HASH`, and constructs the bitcoinj `BlockChain` instance.
- When FAST_API_10POW succeeds, `runWalletSnapshotIfNeeded()` invokes `ApiWalletSnapshotBootstrapper` (API wallet snapshot import) before SPV peers start.
- In API modes the wallet’s `lastBlockSeen*` fields are set to the API tip so SPV will start from `apiTipHeight + 1`.
- `initializeSpv()` ends by calling `startPeerGroup()`, which builds the `PeerGroup`, applies relaxed settings for FAST_API_10POW, and starts block downloads (`peerGroup.startBlockChainDownload()`).
