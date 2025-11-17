import org.bitcoinj.core.*;
import org.bitcoinj.store.*;
import org.bitcoinj.utils.MonetaryFormat;

class StubParams extends NetworkParameters {
    public StubParams() { super(); }
    @Override
    public String getPaymentProtocolId() { return "stub"; }
    @Override
    public void checkDifficultyTransitions(StoredBlock storedPrev, Block next, BlockStore blockStore) {}
    @Override
    public Coin getMaxMoney() { return NetworkParameters.MAX_MONEY; }
    @Override
    public Coin getMinNonDustOutput() { return Coin.SATOSHI; }
    @Override
    public MonetaryFormat getMonetaryFormat() { return MonetaryFormat.BTC; }
    @Override
    public String getUriScheme() { return "stub"; }
    @Override
    public boolean hasMaxMoney() { return true; }
    @Override
    public BitcoinSerializer getSerializer(boolean parseRetain) { return new BitcoinSerializer(this, parseRetain); }
    @Override
    public BitcoinSerializer getSerializer(boolean parseRetain, int protocolVersion) { return new BitcoinSerializer(this, parseRetain); }
    @Override
    public int getProtocolVersionNum(NetworkParameters.ProtocolVersion version) { return version.getBitcoinProtocolVersion(); }
}
var params = new StubParams();
Block genesis = params.getGenesisBlock();
genesis.setDifficultyTarget(0x1e0fffffL);
genesis.setTime(1683850602L);
genesis.setNonce(283486);
System.out.println(genesis);
System.out.println("hash=" + genesis.getHashAsString());
System.out.println("merkle=" + genesis.getMerkleRoot());
