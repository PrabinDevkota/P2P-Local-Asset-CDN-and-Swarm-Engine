package com.prabin.tracker.store;

import com.prabin.swarmedge.common.id.AssetId;

import java.util.List;

/** Peer phone book. Values are compact JSON; never asset file bytes. */
public interface PeerDirectory {

    void save(AssetId assetId, PeerRecord peer);

    List<PeerRecord> list(AssetId assetId);
}
