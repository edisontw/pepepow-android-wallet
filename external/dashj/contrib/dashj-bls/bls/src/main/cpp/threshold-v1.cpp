#include "threshold-v1.hpp"
using namespace std;
namespace bls {

    PrivateKey Threshold::PrivateKeyRecover(const std::vector<PrivateKey>& sks, const std::vector<vector<uint8_t>>& ids) {
        std::vector<bls::Bytes> idsVec;
        idsVec.reserve(ids.size());
        for (size_t i = 0; i < ids.size(); i++) {
            idsVec.emplace_back(ids[i]);
        }
        return Threshold::PrivateKeyRecover(sks, idsVec);
    }

    G1Element Threshold::PublicKeyRecover(const std::vector<G1Element>& sks, const std::vector<vector<uint8_t>>& ids) {
        std::vector<bls::Bytes> idsVec;
        idsVec.reserve(ids.size());
        for (size_t i = 0; i < ids.size(); i++) {
            idsVec.emplace_back(ids[i]);
        }
        return Threshold::PublicKeyRecover(sks, idsVec);
    }


    G2Element Threshold::SignatureRecover(const std::vector<G2Element>& sigs, const std::vector<vector<uint8_t>>& ids) {
        std::vector<bls::Bytes> idsVec;
        idsVec.reserve(ids.size());
        for (size_t i = 0; i < ids.size(); i++) {
            idsVec.emplace_back(ids[i]);

        }
        return Threshold::SignatureRecover(sigs, idsVec);
    }

}

