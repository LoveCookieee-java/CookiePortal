package dev.khoa.plugin.cookieportal.render;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;

final class ViewSession {
   String portalKey;
   String destinationKey;
   int viewerSide;
   int warmupFrames;
   long lastReassertAt;
   final Map<Location, BlockData> sent = new HashMap();
   final Map<Location, BlockData> pending = new HashMap();
   final Map<Location, Integer> confirmations = new HashMap();
   final Map<Location, Integer> missingFrames = new HashMap();
   volatile long localOcclusionGeneration;
   volatile String localOcclusionKey;
   final Map<UUID, Long> hiddenLocalEntities = new ConcurrentHashMap();
}
