import os
import codecs

path = r'c:\Users\yench\pepepow-android-wallet\external\dashj\core\src\main\java\org\bitcoinj\core\AbstractBlockChain.java'

CHUNK_SIZE = 8192

with open(path, 'rb') as f:
    content = f.read()

# Check for UTF-8 BOM
if content.startswith(codecs.BOM_UTF8):
    print("Found UTF-8 BOM. Removing...")
    content = content[len(codecs.BOM_UTF8):]
    with open(path, 'wb') as f:
        f.write(content)
    print("BOM removed.")
else:
    print("No BOM found.")
