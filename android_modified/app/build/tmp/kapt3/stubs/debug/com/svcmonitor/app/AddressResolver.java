package com.svcmonitor.app;

/**
 * Resolves memory addresses to library+offset using /proc/<pid>/maps.
 * Maintains per-PID snapshots with TTL and crash detection.
 * When a process crashes, the last known snapshot is still used for resolution.
 */
@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000J\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0010\t\n\u0000\n\u0002\u0010\b\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010 \n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0002\b\u0007\n\u0002\u0010\u0002\n\u0002\b\u0004\b\u00c6\u0002\u0018\u00002\u00020\u0001:\u0002\u001d\u001eB\u0007\b\u0002\u00a2\u0006\u0002\u0010\u0002J \u0010\r\u001a\u0004\u0018\u00010\u000e2\f\u0010\u000f\u001a\b\u0012\u0004\u0012\u00020\u000e0\u00102\u0006\u0010\u0011\u001a\u00020\u0004H\u0002J!\u0010\u0012\u001a\u00020\u00132\u0006\u0010\u0014\u001a\u00020\u00062\u0006\u0010\u0011\u001a\u00020\u0004H\u0086@\u00f8\u0001\u0000\u00a2\u0006\u0002\u0010\u0015J!\u0010\u0016\u001a\n\u0012\u0004\u0012\u00020\u000e\u0018\u00010\u00102\u0006\u0010\u0014\u001a\u00020\u0006H\u0082@\u00f8\u0001\u0000\u00a2\u0006\u0002\u0010\u0017J\u0016\u0010\u0018\u001a\b\u0012\u0004\u0012\u00020\u000e0\u00102\u0006\u0010\u0019\u001a\u00020\u0013H\u0002J\u000e\u0010\u001a\u001a\u00020\u001b2\u0006\u0010\u0014\u001a\u00020\u0006J!\u0010\u001c\u001a\u00020\u00132\u0006\u0010\u0014\u001a\u00020\u00062\u0006\u0010\u0011\u001a\u00020\u0004H\u0086@\u00f8\u0001\u0000\u00a2\u0006\u0002\u0010\u0015R\u000e\u0010\u0003\u001a\u00020\u0004X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0005\u001a\u00020\u0006X\u0082T\u00a2\u0006\u0002\n\u0000R\u0014\u0010\u0007\u001a\b\u0012\u0004\u0012\u00020\u00060\bX\u0082\u0004\u00a2\u0006\u0002\n\u0000R \u0010\t\u001a\u0014\u0012\u0004\u0012\u00020\u0006\u0012\n\u0012\b\u0012\u0004\u0012\u00020\f0\u000b0\nX\u0082\u0004\u00a2\u0006\u0002\n\u0000\u0082\u0002\u0004\n\u0002\b\u0019\u00a8\u0006\u001f"}, d2 = {"Lcom/svcmonitor/app/AddressResolver;", "", "()V", "MAPS_TTL_MS", "", "MAX_SNAPSHOTS_PER_PID", "", "mapsDisabledForPid", "Ljava/util/HashSet;", "mapsHistory", "Ljava/util/HashMap;", "Ljava/util/ArrayDeque;", "Lcom/svcmonitor/app/AddressResolver$MapsSnapshot;", "findMapRegion", "Lcom/svcmonitor/app/AddressResolver$MapRegion;", "regions", "", "addr", "formatAddrSoOffset", "", "pid", "(IJLkotlin/coroutines/Continuation;)Ljava/lang/Object;", "getMapsRegions", "(ILkotlin/coroutines/Continuation;)Ljava/lang/Object;", "parseMapsRegions", "maps", "resetForPid", "", "resolveAddress", "MapRegion", "MapsSnapshot", "app_debug"})
public final class AddressResolver {
    @org.jetbrains.annotations.NotNull
    private static final java.util.HashMap<java.lang.Integer, java.util.ArrayDeque<com.svcmonitor.app.AddressResolver.MapsSnapshot>> mapsHistory = null;
    @org.jetbrains.annotations.NotNull
    private static final java.util.HashSet<java.lang.Integer> mapsDisabledForPid = null;
    private static final long MAPS_TTL_MS = 5000L;
    private static final int MAX_SNAPSHOTS_PER_PID = 5;
    @org.jetbrains.annotations.NotNull
    public static final com.svcmonitor.app.AddressResolver INSTANCE = null;
    
    private AddressResolver() {
        super();
    }
    
    /**
     * Resets tracking for a PID (e.g., when starting to monitor a new app).
     */
    public final void resetForPid(int pid) {
    }
    
    /**
     * Resolves an address to a human-readable string: "library+offset (0xaddr)"
     * or "unmapped" if not found.
     * Falls back to the last known snapshot if the process has crashed.
     */
    @org.jetbrains.annotations.Nullable
    public final java.lang.Object resolveAddress(int pid, long addr, @org.jetbrains.annotations.NotNull
    kotlin.coroutines.Continuation<? super java.lang.String> $completion) {
        return null;
    }
    
    /**
     * Returns "library+offset (0xaddr)" if resolved, or "0xaddr (unmapped)" otherwise.
     */
    @org.jetbrains.annotations.Nullable
    public final java.lang.Object formatAddrSoOffset(int pid, long addr, @org.jetbrains.annotations.NotNull
    kotlin.coroutines.Continuation<? super java.lang.String> $completion) {
        return null;
    }
    
    private final java.lang.Object getMapsRegions(int pid, kotlin.coroutines.Continuation<? super java.util.List<com.svcmonitor.app.AddressResolver.MapRegion>> $completion) {
        return null;
    }
    
    private final java.util.List<com.svcmonitor.app.AddressResolver.MapRegion> parseMapsRegions(java.lang.String maps) {
        return null;
    }
    
    private final com.svcmonitor.app.AddressResolver.MapRegion findMapRegion(java.util.List<com.svcmonitor.app.AddressResolver.MapRegion> regions, long addr) {
        return null;
    }
    
    @kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000*\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\t\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0002\b\u0011\n\u0002\u0010\u000b\n\u0002\b\u0002\n\u0002\u0010\b\n\u0002\b\u0002\b\u0082\b\u0018\u00002\u00020\u0001B-\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u0012\u0006\u0010\u0004\u001a\u00020\u0003\u0012\u0006\u0010\u0005\u001a\u00020\u0006\u0012\u0006\u0010\u0007\u001a\u00020\u0003\u0012\u0006\u0010\b\u001a\u00020\u0006\u00a2\u0006\u0002\u0010\tJ\t\u0010\u0011\u001a\u00020\u0003H\u00c6\u0003J\t\u0010\u0012\u001a\u00020\u0003H\u00c6\u0003J\t\u0010\u0013\u001a\u00020\u0006H\u00c6\u0003J\t\u0010\u0014\u001a\u00020\u0003H\u00c6\u0003J\t\u0010\u0015\u001a\u00020\u0006H\u00c6\u0003J;\u0010\u0016\u001a\u00020\u00002\b\b\u0002\u0010\u0002\u001a\u00020\u00032\b\b\u0002\u0010\u0004\u001a\u00020\u00032\b\b\u0002\u0010\u0005\u001a\u00020\u00062\b\b\u0002\u0010\u0007\u001a\u00020\u00032\b\b\u0002\u0010\b\u001a\u00020\u0006H\u00c6\u0001J\u0013\u0010\u0017\u001a\u00020\u00182\b\u0010\u0019\u001a\u0004\u0018\u00010\u0001H\u00d6\u0003J\t\u0010\u001a\u001a\u00020\u001bH\u00d6\u0001J\t\u0010\u001c\u001a\u00020\u0006H\u00d6\u0001R\u0011\u0010\u0004\u001a\u00020\u0003\u00a2\u0006\b\n\u0000\u001a\u0004\b\n\u0010\u000bR\u0011\u0010\u0007\u001a\u00020\u0003\u00a2\u0006\b\n\u0000\u001a\u0004\b\f\u0010\u000bR\u0011\u0010\b\u001a\u00020\u0006\u00a2\u0006\b\n\u0000\u001a\u0004\b\r\u0010\u000eR\u0011\u0010\u0005\u001a\u00020\u0006\u00a2\u0006\b\n\u0000\u001a\u0004\b\u000f\u0010\u000eR\u0011\u0010\u0002\u001a\u00020\u0003\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0010\u0010\u000b\u00a8\u0006\u001d"}, d2 = {"Lcom/svcmonitor/app/AddressResolver$MapRegion;", "", "start", "", "end", "perms", "", "mapOffset", "path", "(JJLjava/lang/String;JLjava/lang/String;)V", "getEnd", "()J", "getMapOffset", "getPath", "()Ljava/lang/String;", "getPerms", "getStart", "component1", "component2", "component3", "component4", "component5", "copy", "equals", "", "other", "hashCode", "", "toString", "app_debug"})
    static final class MapRegion {
        private final long start = 0L;
        private final long end = 0L;
        @org.jetbrains.annotations.NotNull
        private final java.lang.String perms = null;
        private final long mapOffset = 0L;
        @org.jetbrains.annotations.NotNull
        private final java.lang.String path = null;
        
        public MapRegion(long start, long end, @org.jetbrains.annotations.NotNull
        java.lang.String perms, long mapOffset, @org.jetbrains.annotations.NotNull
        java.lang.String path) {
            super();
        }
        
        public final long getStart() {
            return 0L;
        }
        
        public final long getEnd() {
            return 0L;
        }
        
        @org.jetbrains.annotations.NotNull
        public final java.lang.String getPerms() {
            return null;
        }
        
        public final long getMapOffset() {
            return 0L;
        }
        
        @org.jetbrains.annotations.NotNull
        public final java.lang.String getPath() {
            return null;
        }
        
        public final long component1() {
            return 0L;
        }
        
        public final long component2() {
            return 0L;
        }
        
        @org.jetbrains.annotations.NotNull
        public final java.lang.String component3() {
            return null;
        }
        
        public final long component4() {
            return 0L;
        }
        
        @org.jetbrains.annotations.NotNull
        public final java.lang.String component5() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull
        public final com.svcmonitor.app.AddressResolver.MapRegion copy(long start, long end, @org.jetbrains.annotations.NotNull
        java.lang.String perms, long mapOffset, @org.jetbrains.annotations.NotNull
        java.lang.String path) {
            return null;
        }
        
        @java.lang.Override
        public boolean equals(@org.jetbrains.annotations.Nullable
        java.lang.Object other) {
            return false;
        }
        
        @java.lang.Override
        public int hashCode() {
            return 0;
        }
        
        @java.lang.Override
        @org.jetbrains.annotations.NotNull
        public java.lang.String toString() {
            return null;
        }
    }
    
    @kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u00000\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\t\n\u0000\n\u0002\u0010 \n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\b\n\u0002\b\u000f\n\u0002\u0010\u000b\n\u0002\b\u0003\n\u0002\u0010\u000e\n\u0000\b\u0082\b\u0018\u00002\u00020\u0001B+\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u0012\f\u0010\u0004\u001a\b\u0012\u0004\u0012\u00020\u00060\u0005\u0012\u0006\u0010\u0007\u001a\u00020\b\u0012\u0006\u0010\t\u001a\u00020\u0003\u00a2\u0006\u0002\u0010\nJ\t\u0010\u0012\u001a\u00020\u0003H\u00c6\u0003J\u000f\u0010\u0013\u001a\b\u0012\u0004\u0012\u00020\u00060\u0005H\u00c6\u0003J\t\u0010\u0014\u001a\u00020\bH\u00c6\u0003J\t\u0010\u0015\u001a\u00020\u0003H\u00c6\u0003J7\u0010\u0016\u001a\u00020\u00002\b\b\u0002\u0010\u0002\u001a\u00020\u00032\u000e\b\u0002\u0010\u0004\u001a\b\u0012\u0004\u0012\u00020\u00060\u00052\b\b\u0002\u0010\u0007\u001a\u00020\b2\b\b\u0002\u0010\t\u001a\u00020\u0003H\u00c6\u0001J\u0013\u0010\u0017\u001a\u00020\u00182\b\u0010\u0019\u001a\u0004\u0018\u00010\u0001H\u00d6\u0003J\t\u0010\u001a\u001a\u00020\bH\u00d6\u0001J\t\u0010\u001b\u001a\u00020\u001cH\u00d6\u0001R\u0011\u0010\u0007\u001a\u00020\b\u00a2\u0006\b\n\u0000\u001a\u0004\b\u000b\u0010\fR\u0017\u0010\u0004\u001a\b\u0012\u0004\u0012\u00020\u00060\u0005\u00a2\u0006\b\n\u0000\u001a\u0004\b\r\u0010\u000eR\u0011\u0010\t\u001a\u00020\u0003\u00a2\u0006\b\n\u0000\u001a\u0004\b\u000f\u0010\u0010R\u0011\u0010\u0002\u001a\u00020\u0003\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0011\u0010\u0010\u00a8\u0006\u001d"}, d2 = {"Lcom/svcmonitor/app/AddressResolver$MapsSnapshot;", "", "tsMs", "", "regions", "", "Lcom/svcmonitor/app/AddressResolver$MapRegion;", "regionCount", "", "totalSize", "(JLjava/util/List;IJ)V", "getRegionCount", "()I", "getRegions", "()Ljava/util/List;", "getTotalSize", "()J", "getTsMs", "component1", "component2", "component3", "component4", "copy", "equals", "", "other", "hashCode", "toString", "", "app_debug"})
    static final class MapsSnapshot {
        private final long tsMs = 0L;
        @org.jetbrains.annotations.NotNull
        private final java.util.List<com.svcmonitor.app.AddressResolver.MapRegion> regions = null;
        private final int regionCount = 0;
        private final long totalSize = 0L;
        
        public MapsSnapshot(long tsMs, @org.jetbrains.annotations.NotNull
        java.util.List<com.svcmonitor.app.AddressResolver.MapRegion> regions, int regionCount, long totalSize) {
            super();
        }
        
        public final long getTsMs() {
            return 0L;
        }
        
        @org.jetbrains.annotations.NotNull
        public final java.util.List<com.svcmonitor.app.AddressResolver.MapRegion> getRegions() {
            return null;
        }
        
        public final int getRegionCount() {
            return 0;
        }
        
        public final long getTotalSize() {
            return 0L;
        }
        
        public final long component1() {
            return 0L;
        }
        
        @org.jetbrains.annotations.NotNull
        public final java.util.List<com.svcmonitor.app.AddressResolver.MapRegion> component2() {
            return null;
        }
        
        public final int component3() {
            return 0;
        }
        
        public final long component4() {
            return 0L;
        }
        
        @org.jetbrains.annotations.NotNull
        public final com.svcmonitor.app.AddressResolver.MapsSnapshot copy(long tsMs, @org.jetbrains.annotations.NotNull
        java.util.List<com.svcmonitor.app.AddressResolver.MapRegion> regions, int regionCount, long totalSize) {
            return null;
        }
        
        @java.lang.Override
        public boolean equals(@org.jetbrains.annotations.Nullable
        java.lang.Object other) {
            return false;
        }
        
        @java.lang.Override
        public int hashCode() {
            return 0;
        }
        
        @java.lang.Override
        @org.jetbrains.annotations.NotNull
        public java.lang.String toString() {
            return null;
        }
    }
}