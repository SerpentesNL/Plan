package com.djrapitops.plan.system.settings.network;

import com.djrapitops.plan.api.exceptions.EnableException;
import com.djrapitops.plan.system.SubSystem;
import com.djrapitops.plan.system.database.DBSystem;
import com.djrapitops.plan.system.database.databases.Database;
import com.djrapitops.plan.system.file.PlanFiles;
import com.djrapitops.plan.system.info.server.ServerInfo;
import com.djrapitops.plan.system.settings.config.Config;
import com.djrapitops.plan.system.settings.config.ConfigReader;
import com.djrapitops.plan.system.settings.config.ConfigWriter;
import com.djrapitops.plan.system.settings.config.PlanConfig;
import com.djrapitops.plan.system.settings.paths.TimeSettings;
import com.djrapitops.plan.system.tasks.TaskSystem;
import com.djrapitops.plan.utilities.file.FileWatcher;
import com.djrapitops.plan.utilities.file.WatchedFile;
import com.djrapitops.plugin.api.TimeAmount;
import com.djrapitops.plugin.logging.console.PluginLogger;
import com.djrapitops.plugin.logging.error.ErrorHandler;
import com.djrapitops.plugin.task.AbsRunnable;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * In charge of updating server-network config.
 * <p>
 * Performs following tasks related to the config:
 * - File modification watching related to config.yml
 * - Database updating related to config.yml
 * - File update operations from database related to config.yml
 *
 * @author Rsl1122
 */
@Singleton
public class ServerSettingsManager implements SubSystem {

    private final PlanFiles files;
    private final PlanConfig config;
    private final DBSystem dbSystem;
    private final TaskSystem taskSystem;
    private final ErrorHandler errorHandler;
    private final UUID serverUUID;
    private PluginLogger logger;
    private FileWatcher watcher;

    @Inject
    public ServerSettingsManager(
            PlanFiles files,
            PlanConfig config,
            DBSystem dbSystem,
            ServerInfo serverInfo,
            TaskSystem taskSystem,
            PluginLogger logger,
            ErrorHandler errorHandler
    ) {
        this.files = files;
        this.config = config;
        this.dbSystem = dbSystem;
        this.taskSystem = taskSystem;
        this.logger = logger;
        this.errorHandler = errorHandler;
        serverUUID = serverInfo.getServerUUID();
    }

    @Override
    public void enable() throws EnableException {
        watcher = prepareFileWatcher();
        scheduleDBCheckTask();
    }

    private FileWatcher prepareFileWatcher() {
        FileWatcher fileWatcher = new FileWatcher(files.getDataFolder(), errorHandler);
        File configFile = files.getConfigFile();
        fileWatcher.addToWatchlist(new WatchedFile(configFile,
                () -> updateConfigInDB(configFile)
        ));
        return fileWatcher;
    }

    private void updateConfigInDB(File file) {
        if (!file.exists()) {
            return;
        }

        Database database = dbSystem.getDatabase();

        try {
            Config config = new ConfigReader(file.toPath()).read();
            database.save().saveConfig(serverUUID, config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void scheduleDBCheckTask() {
        long checkPeriod = TimeAmount.toTicks(config.get(TimeSettings.CONFIG_UPDATE_INTERVAL), TimeUnit.MINUTES);
        taskSystem.registerTask("Config Update DB Checker", new AbsRunnable() {
            @Override
            public void run() {
                checkDBForNewConfigSettings(dbSystem.getDatabase());
            }
        }).runTaskTimerAsynchronously(checkPeriod, checkPeriod);
    }

    private void checkDBForNewConfigSettings(Database database) {
        File configFile = files.getConfigFile();
        long lastModified = configFile.exists() ? configFile.lastModified() : -1;

        Optional<Config> foundConfig = database.fetch().getNewConfig(lastModified, serverUUID);
        if (foundConfig.isPresent()) {
            try {
                new ConfigWriter(configFile.toPath()).write(foundConfig.get());
                logger.info("The Config was updated to match one on the Proxy. Reload for changes to take effect.");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public void disable() {
        if (watcher != null) {
            watcher.interrupt();
        }
    }
}
