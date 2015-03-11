/*
 * Copyright 2011-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.config;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.common.Encryption;
import org.glowroot.markers.OnlyUsedByTests;

import static com.google.common.base.Preconditions.checkState;

public class ConfigService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigService.class);

    private final ConfigFile configFile;
    private final ImmutableList<PluginDescriptor> pluginDescriptors;
    private final File secretFile;
    private final Object writeLock = new Object();

    private final Set<ConfigListener> configListeners = Sets.newCopyOnWriteArraySet();
    private final Multimap<String, ConfigListener> pluginConfigListeners =
            Multimaps.synchronizedMultimap(ArrayListMultimap.<String, ConfigListener>create());

    private volatile Config config;

    // volatile not needed as access is guarded by secretKeyFile
    private @MonotonicNonNull SecretKey secretKey;

    ConfigService(File dataDir, List<PluginDescriptor> pluginDescriptors) {
        configFile = new ConfigFile(new File(dataDir, "config.json"), pluginDescriptors);
        this.pluginDescriptors = ImmutableList.copyOf(pluginDescriptors);
        secretFile = new File(dataDir, "secret");
        try {
            config = configFile.loadConfig();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            config = configFile.getDefaultConfig();
        }
    }

    public GeneralConfig getGeneralConfig() {
        return config.generalConfig();
    }

    public UserInterfaceConfig getUserInterfaceConfig() {
        return config.userInterfaceConfig();
    }

    public StorageConfig getStorageConfig() {
        return config.storageConfig();
    }

    public SmtpConfig getSmtpConfig() {
        return config.smtpConfig();
    }

    public UserRecordingConfig getUserRecordingConfig() {
        return config.userRecordingConfig();
    }

    public AdvancedConfig getAdvancedConfig() {
        return config.advancedConfig();
    }

    public @Nullable PluginConfig getPluginConfig(String pluginId) {
        for (PluginConfig pluginConfig : config.pluginConfigs()) {
            if (pluginId.equals(pluginConfig.id())) {
                return pluginConfig;
            }
        }
        return null;
    }

    public List<InstrumentationConfig> getInstrumentationConfigs() {
        return config.instrumentationConfigs();
    }

    public @Nullable InstrumentationConfig getInstrumentationConfig(String version) {
        for (InstrumentationConfig instrumentationConfig : config.instrumentationConfigs()) {
            if (instrumentationConfig.version().equals(version)) {
                return instrumentationConfig;
            }
        }
        return null;
    }

    public List<GaugeConfig> getGaugeConfigs() {
        return config.gaugeConfigs();
    }

    public @Nullable GaugeConfig getGaugeConfig(String version) {
        for (GaugeConfig gaugeConfig : config.gaugeConfigs()) {
            if (gaugeConfig.version().equals(version)) {
                return gaugeConfig;
            }
        }
        return null;
    }

    public List<AlertConfig> getAlertConfigs() {
        return config.alertConfigs();
    }

    public @Nullable AlertConfig getAlertConfig(String version) {
        for (AlertConfig alertConfig : config.alertConfigs()) {
            if (alertConfig.version().equals(version)) {
                return alertConfig;
            }
        }
        return null;
    }

    public void addConfigListener(ConfigListener listener) {
        configListeners.add(listener);
    }

    public void addPluginConfigListener(String pluginId, ConfigListener listener) {
        pluginConfigListeners.put(pluginId, listener);
    }

    public String updateGeneralConfig(GeneralConfig generalConfig, String priorVersion)
            throws Exception {
        boolean notifyPluginConfigListeners;
        synchronized (writeLock) {
            checkVersionsEqual(config.generalConfig().version(), priorVersion);
            boolean previousEnabled = config.generalConfig().enabled();
            Config updatedConfig = ((ImmutableConfig) config).withGeneralConfig(generalConfig);
            configFile.write(updatedConfig);
            config = updatedConfig;
            notifyPluginConfigListeners = config.generalConfig().enabled() != previousEnabled;
        }
        notifyConfigListeners();
        if (notifyPluginConfigListeners) {
            notifyAllPluginConfigListeners();
        }
        return generalConfig.version();
    }

    public String updateUserInterfaceConfig(UserInterfaceConfig userInterfaceConfig,
            String priorVersion) throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(config.userInterfaceConfig().version(), priorVersion);
            Config updatedConfig =
                    ((ImmutableConfig) config).withUserInterfaceConfig(userInterfaceConfig);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return userInterfaceConfig.version();
    }

    public String updateStorageConfig(StorageConfig storageConfig, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(config.storageConfig().version(), priorVersion);
            Config updatedConfig = ((ImmutableConfig) config).withStorageConfig(storageConfig);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return storageConfig.version();
    }

    public String updateSmtpConfig(SmtpConfig smtpConfig, String priorVersion) throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(config.smtpConfig().version(), priorVersion);
            Config updatedConfig = ((ImmutableConfig) config).withSmtpConfig(smtpConfig);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return smtpConfig.version();
    }

    public String updateUserRecordingConfig(UserRecordingConfig userRecordingConfig,
            String priorVersion) throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(config.userRecordingConfig().version(), priorVersion);
            Config updatedConfig =
                    ((ImmutableConfig) config).withUserRecordingConfig(userRecordingConfig);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return userRecordingConfig.version();
    }

    public String updateAdvancedConfig(AdvancedConfig advancedConfig, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(config.advancedConfig().version(), priorVersion);
            Config updatedConfig = ((ImmutableConfig) config).withAdvancedConfig(advancedConfig);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return advancedConfig.version();
    }

    public String updatePluginConfig(PluginConfig pluginConfig, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            List<PluginConfig> pluginConfigs = Lists.newArrayList(config.pluginConfigs());
            boolean found = false;
            for (ListIterator<PluginConfig> i = pluginConfigs.listIterator(); i.hasNext();) {
                PluginConfig loopPluginConfig = i.next();
                if (pluginConfig.id().equals(loopPluginConfig.id())) {
                    checkVersionsEqual(loopPluginConfig.version(), priorVersion);
                    i.set(pluginConfig);
                    found = true;
                    break;
                }
            }
            checkState(found, "Plugin config not found: %s", pluginConfig.id());
            Config updatedConfig = ((ImmutableConfig) config).withPluginConfigs(pluginConfigs);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyPluginConfigListeners(pluginConfig.id());
        return pluginConfig.version();
    }

    public String insertInstrumentationConfig(InstrumentationConfig instrumentationConfig)
            throws IOException {
        synchronized (writeLock) {
            List<InstrumentationConfig> configs =
                    Lists.newArrayList(config.instrumentationConfigs());
            configs.add(instrumentationConfig);
            Config updatedConfig = ((ImmutableConfig) config).withInstrumentationConfigs(configs);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return instrumentationConfig.version();
    }

    public String updateInstrumentationConfig(InstrumentationConfig instrumentationConfig,
            String priorVersion) throws IOException {
        synchronized (writeLock) {
            List<InstrumentationConfig> configs =
                    Lists.newArrayList(config.instrumentationConfigs());
            boolean found = false;
            for (ListIterator<InstrumentationConfig> i = configs.listIterator(); i.hasNext();) {
                if (priorVersion.equals(i.next().version())) {
                    i.set(instrumentationConfig);
                    found = true;
                    break;
                }
            }
            checkState(found, "Instrumentation config not found: %s", priorVersion);
            Config updatedConfig = ((ImmutableConfig) config).withInstrumentationConfigs(configs);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return instrumentationConfig.version();
    }

    public void deleteInstrumentationConfig(String version) throws IOException {
        synchronized (writeLock) {
            List<InstrumentationConfig> configs =
                    Lists.newArrayList(config.instrumentationConfigs());
            boolean found = false;
            for (ListIterator<InstrumentationConfig> i = configs.listIterator(); i.hasNext();) {
                if (version.equals(i.next().version())) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            checkState(found, "Instrumentation config not found: %s", version);
            Config updatedConfig = ((ImmutableConfig) config).withInstrumentationConfigs(configs);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
    }

    public String insertGaugeConfig(GaugeConfig gaugeConfig) throws Exception {
        synchronized (writeLock) {
            List<GaugeConfig> gaugeConfigs = Lists.newArrayList(config.gaugeConfigs());
            // check for duplicate mbeanObjectName
            for (GaugeConfig loopConfig : gaugeConfigs) {
                if (loopConfig.mbeanObjectName().equals(gaugeConfig.mbeanObjectName())) {
                    throw new DuplicateMBeanObjectNameException();
                }
            }
            gaugeConfigs.add(gaugeConfig);
            Config updatedConfig = ((ImmutableConfig) config).withGaugeConfigs(gaugeConfigs);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return gaugeConfig.version();
    }

    public String updateGaugeConfig(GaugeConfig gaugeConfig, String priorVersion) throws Exception {
        synchronized (writeLock) {
            List<GaugeConfig> gaugeConfigs = Lists.newArrayList(config.gaugeConfigs());
            boolean found = false;
            for (ListIterator<GaugeConfig> i = gaugeConfigs.listIterator(); i.hasNext();) {
                GaugeConfig loopConfig = i.next();
                if (priorVersion.equals(loopConfig.version())) {
                    i.set(gaugeConfig);
                    found = true;
                    break;
                } else if (loopConfig.mbeanObjectName().equals(gaugeConfig.mbeanObjectName())) {
                    throw new DuplicateMBeanObjectNameException();
                }
            }
            checkState(found, "Gauge config not found: %s", priorVersion);
            Config updatedConfig = ((ImmutableConfig) config).withGaugeConfigs(gaugeConfigs);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return gaugeConfig.version();
    }

    public void deleteGaugeConfig(String version) throws IOException {
        synchronized (writeLock) {
            List<GaugeConfig> gaugeConfigs = Lists.newArrayList(config.gaugeConfigs());
            boolean found = false;
            for (ListIterator<GaugeConfig> i = gaugeConfigs.listIterator(); i.hasNext();) {
                if (version.equals(i.next().version())) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            checkState(found, "Gauge config not found: %s", version);
            Config updatedConfig = ((ImmutableConfig) config).withGaugeConfigs(gaugeConfigs);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
    }

    public String insertAlertConfig(AlertConfig alertConfig) throws Exception {
        synchronized (writeLock) {
            List<AlertConfig> alertConfigs = Lists.newArrayList(config.alertConfigs());
            alertConfigs.add(alertConfig);
            Config updatedConfig = ((ImmutableConfig) config).withAlertConfigs(alertConfigs);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return alertConfig.version();
    }

    public String updateAlertConfig(AlertConfig alertConfig, String priorVersion)
            throws IOException {
        synchronized (writeLock) {
            List<AlertConfig> alertConfigs = Lists.newArrayList(config.alertConfigs());
            boolean found = false;
            for (ListIterator<AlertConfig> i = alertConfigs.listIterator(); i.hasNext();) {
                if (priorVersion.equals(i.next().version())) {
                    i.set(alertConfig);
                    found = true;
                    break;
                }
            }
            checkState(found, "Alert config not found: %s", priorVersion);
            Config updatedConfig = ((ImmutableConfig) config).withAlertConfigs(alertConfigs);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return alertConfig.version();
    }

    public void deleteAlertConfig(String version) throws IOException {
        synchronized (writeLock) {
            List<AlertConfig> alertConfigs = Lists.newArrayList(config.alertConfigs());
            boolean found = false;
            for (ListIterator<AlertConfig> i = alertConfigs.listIterator(); i.hasNext();) {
                if (version.equals(i.next().version())) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            checkState(found, "Alert config not found: %s", version);
            Config updatedConfig = ((ImmutableConfig) config).withAlertConfigs(alertConfigs);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
    }

    public String getDefaultTransactionType() {
        String defaultTransactionType = config.generalConfig().defaultTransactionType();
        if (!defaultTransactionType.isEmpty()) {
            return defaultTransactionType;
        }
        return configFile.getDefaultTransactionType(config.instrumentationConfigs());
    }

    public ImmutableList<String> getAllTransactionTypes() {
        Set<String> transactionTypes = Sets.newLinkedHashSet();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            transactionTypes.addAll(pluginDescriptor.transactionTypes());
        }
        for (InstrumentationConfig config : getInstrumentationConfigs()) {
            String transactionType = config.transactionType();
            if (!transactionType.isEmpty()) {
                transactionTypes.add(transactionType);
            }
        }
        return ImmutableList.copyOf(transactionTypes);
    }

    // lazy create secret file only when needed
    public SecretKey getSecretKey() throws Exception {
        synchronized (secretFile) {
            if (secretKey == null) {
                if (secretFile.exists()) {
                    secretKey = Encryption.loadKey(secretFile);
                } else {
                    secretKey = Encryption.generateNewKey();
                    Files.write(secretKey.getEncoded(), secretFile);
                }
            }
            return secretKey;
        }
    }

    private void checkVersionsEqual(String version, String priorVersion)
            throws OptimisticLockException {
        if (!version.equals(priorVersion)) {
            throw new OptimisticLockException();
        }
    }

    // the updated config is not passed to the listeners to avoid the race condition of multiple
    // config updates being sent out of order, instead listeners must call get*Config() which will
    // never return the updates out of order (at worst it may return the most recent update twice
    // which is ok)
    private void notifyConfigListeners() {
        for (ConfigListener configListener : configListeners) {
            configListener.onChange();
        }
    }

    private void notifyPluginConfigListeners(String pluginId) {
        // make copy first to avoid possible ConcurrentModificationException while iterating
        Collection<ConfigListener> listeners =
                ImmutableList.copyOf(pluginConfigListeners.get(pluginId));
        for (ConfigListener listener : listeners) {
            listener.onChange();
        }
    }

    private void notifyAllPluginConfigListeners() {
        // make copy first to avoid possible ConcurrentModificationException while iterating
        Collection<ConfigListener> listeners =
                ImmutableList.copyOf(pluginConfigListeners.values());
        for (ConfigListener configListener : listeners) {
            configListener.onChange();
        }
    }

    @OnlyUsedByTests
    public void resetAllConfig() throws IOException {
        configFile.delete();
        config = configFile.loadConfig();
        notifyConfigListeners();
        notifyAllPluginConfigListeners();
    }

    @SuppressWarnings("serial")
    public static class OptimisticLockException extends Exception {}

    @SuppressWarnings("serial")
    public static class DuplicateMBeanObjectNameException extends Exception {}
}
