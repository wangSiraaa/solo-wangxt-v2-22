package com.park.energy.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String timezone = "Asia/Shanghai";
    private double missingGapFactor = 1.5;
    private String billingCurrency = "CNY";
    private final Seed seed = new Seed();

    public String timezone() { return timezone; }
    public double missingGapFactor() { return missingGapFactor; }
    public String billingCurrency() { return billingCurrency; }
    public Seed seed() { return seed; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public double getMissingGapFactor() { return missingGapFactor; }
    public void setMissingGapFactor(double missingGapFactor) { this.missingGapFactor = missingGapFactor; }
    public String getBillingCurrency() { return billingCurrency; }
    public void setBillingCurrency(String billingCurrency) { this.billingCurrency = billingCurrency; }
    public Seed getSeed() { return seed; }

    public static class Seed {
        private boolean enabled = true;
        private String samplesDir = "./data/samples";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getSamplesDir() { return samplesDir; }
        public void setSamplesDir(String samplesDir) { this.samplesDir = samplesDir; }
    }
}
