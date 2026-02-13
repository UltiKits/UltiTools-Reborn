package com.ultikits.plugins.cleaner.service;

import com.ultikits.plugins.cleaner.config.CleanerConfig;
import com.ultikits.plugins.cleaner.utils.ServerTypeUtil;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.annotations.Service;

/**
 * TPS-aware scheduler for adaptive cleanup thresholds.
 * Monitors server TPS and adjusts cleanup aggressiveness accordingly.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service
public class TpsAwareScheduler {

    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private CleanerConfig config;
    
    // Fallback TPS calculation
    private long lastTickTime = System.currentTimeMillis();
    private final double[] tpsHistory1m = new double[60];
    private final double[] tpsHistory5m = new double[300];
    private final double[] tpsHistory15m = new double[900];
    private int historyIndex = 0;
    private boolean fallbackMonitorEnabled = false;
    
    /**
     * Initialize the TPS monitor.
     */
    public void init() {
        // Enable fallback monitoring if native TPS is not available
        fallbackMonitorEnabled = !ServerTypeUtil.hasTpsMethod();
        if (fallbackMonitorEnabled) {
            lastTickTime = System.currentTimeMillis();
        }
        plugin.getLogger().info("TPS monitor initialized. Server: " + ServerTypeUtil.getServerSoftware());
    }

    /**
     * Shutdown the TPS monitor.
     * Note: @Scheduled tasks are automatically cancelled by the framework.
     */
    public void shutdown() {
        // No manual task cancellation needed
    }
    
    /**
     * Update fallback TPS calculation.
     * Runs every second (20 ticks) for servers without native TPS API.
     */
    @Scheduled(period = 20, async = false)
    public void updateFallbackTps() {
        if (!fallbackMonitorEnabled) {
            return;
        }

        long now = System.currentTimeMillis();
        long diff = now - lastTickTime;
        lastTickTime = now;
        
        // Calculate TPS (1000ms / actual ms per tick)
        double tps = 1000.0 / Math.max(diff / 20.0, 50.0);
        tps = Math.min(tps, 20.0);
        
        // Store in history
        tpsHistory1m[historyIndex % 60] = tps;
        tpsHistory5m[historyIndex % 300] = tps;
        tpsHistory15m[historyIndex % 900] = tps;
        historyIndex++;
    }
    
    /**
     * Get current TPS based on configured sample window.
     * 
     * @return current TPS value
     */
    public double getCurrentTps() {
        if (!config.isTpsAdaptiveEnabled()) {
            return 20.0; // Return perfect TPS if adaptive is disabled
        }
        
        // Try native TPS first
        double[] nativeTps = ServerTypeUtil.getServerTps();
        if (nativeTps != null && nativeTps.length >= 3) {
            return getTpsBySampleWindow(nativeTps);
        }
        
        // Fallback to calculated TPS
        return getTpsBySampleWindow(calculateFallbackTps());
    }
    
    /**
     * Get TPS value based on configured sample window.
     */
    private double getTpsBySampleWindow(double[] tps) {
        String window = config.getTpsSampleWindow();
        switch (window.toLowerCase()) {
            case "5m":
                return tps.length > 1 ? tps[1] : tps[0];
            case "15m":
                return tps.length > 2 ? tps[2] : tps[0];
            case "1m":
            default:
                return tps[0];
        }
    }
    
    /**
     * Calculate fallback TPS values.
     */
    private double[] calculateFallbackTps() {
        double[] tps = new double[3];
        tps[0] = calculateAverage(tpsHistory1m, Math.min(historyIndex, 60));
        tps[1] = calculateAverage(tpsHistory5m, Math.min(historyIndex, 300));
        tps[2] = calculateAverage(tpsHistory15m, Math.min(historyIndex, 900));
        return tps;
    }
    
    /**
     * Calculate average from history array.
     */
    private double calculateAverage(double[] history, int count) {
        if (count <= 0) return 20.0;
        double sum = 0;
        for (int i = 0; i < count; i++) {
            sum += history[i];
        }
        return sum / count;
    }
    
    /**
     * Check if TPS is below low threshold.
     * 
     * @return true if TPS is low
     */
    public boolean isLowTps() {
        return getCurrentTps() < config.getLowTpsThreshold();
    }
    
    /**
     * Check if TPS is below critical threshold.
     * 
     * @return true if TPS is critical
     */
    public boolean isCriticalTps() {
        return getCurrentTps() < config.getCriticalTpsThreshold();
    }
    
    /**
     * Get the threshold reduction multiplier based on current TPS.
     * Returns 1.0 for normal TPS, reduced value for low TPS.
     * 
     * @return threshold multiplier (0.0 - 1.0)
     */
    public double getThresholdMultiplier() {
        if (!config.isTpsAdaptiveEnabled()) {
            return 1.0;
        }
        
        if (isCriticalTps()) {
            return 1.0 - (config.getCriticalTpsReduction() / 100.0);
        } else if (isLowTps()) {
            return 1.0 - (config.getLowTpsReduction() / 100.0);
        }
        return 1.0;
    }
    
    /**
     * Apply threshold reduction to a value.
     * 
     * @param originalThreshold the original threshold value
     * @return adjusted threshold
     */
    public int applyThresholdReduction(int originalThreshold) {
        return (int) (originalThreshold * getThresholdMultiplier());
    }
    
    /**
     * Get TPS status description.
     * 
     * @return status string
     */
    public String getTpsStatus() {
        double tps = getCurrentTps();
        if (isCriticalTps()) {
            return String.format("§c%.2f (Critical)", tps);
        } else if (isLowTps()) {
            return String.format("§e%.2f (Low)", tps);
        } else {
            return String.format("§a%.2f (Normal)", tps);
        }
    }
}
