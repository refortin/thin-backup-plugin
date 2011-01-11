package org.jvnet.hudson.plugins.thinbackup;

import hudson.Extension;
import hudson.model.ManagementLink;
import hudson.model.TaskListener;
import hudson.model.Hudson;
import hudson.scheduler.CronTab;
import hudson.triggers.Trigger;
import hudson.util.FormValidation;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.jvnet.hudson.plugins.thinbackup.ThinBackupPeriodicWork.BackupType;
import org.jvnet.hudson.plugins.thinbackup.restore.HudsonRestore;
import org.jvnet.hudson.plugins.thinbackup.utils.Utils;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import antlr.ANTLRException;

@Extension
public class ThinBackupMgmtLink extends ManagementLink {
  private static final Logger LOGGER = Logger.getLogger("hudson.plugins.thinbackup");

  public String getDisplayName() {
    return "ThinBackup";
  }

  @Override
  public String getIconFileName() {
    return "package.gif";
  }

  @Override
  public String getUrlName() {
    return "thinBackup";
  }

  @Override
  public String getDescription() {
    return "Backup your global and job specific configuration.";
  }

  public void doBackupManual(final StaplerRequest res, final StaplerResponse rsp) throws IOException {
    Hudson.getInstance().checkPermission(Hudson.ADMINISTER);

    final ThinBackupPeriodicWork manualBackupWorker = new ThinBackupPeriodicWork() {
      @Override
      protected void execute(final TaskListener arg0) throws IOException, InterruptedException {
        backupNow(BackupType.FULL);
      }
    };
    Trigger.timer.schedule(manualBackupWorker, 0);

    LOGGER.info("Manual backup finished");
    rsp.sendRedirect(res.getContextPath() + "/thinBackup");
  }

  public void doRestore(final StaplerRequest res, final StaplerResponse rsp,
      @QueryParameter("restoreBackupFrom") final String restoreBackupFrom) throws IOException {
    Hudson.getInstance().checkPermission(Hudson.ADMINISTER);
    final Hudson hudson = Hudson.getInstance();
    hudson.doQuietDown();
    LOGGER.fine("Wait until executors are idle to perform restore.");
    Utils.waitUntilIdle();

    final File hudsonHome = Hudson.getInstance().getRootDir();

    final HudsonRestore hudsonRestore = new HudsonRestore(hudsonHome, ThinBackupPluginImpl.getInstance()
        .getBackupPath(), restoreBackupFrom);
    hudsonRestore.prepare();
    hudsonRestore.restore();

    hudson.doCancelQuietDown();
    LOGGER.info("Restore finished.");
    rsp.sendRedirect(res.getContextPath() + "/thinBackup");
  }

  public void doSaveSettings(final StaplerRequest res, final StaplerResponse rsp,
      @QueryParameter("backupPath") final String backupPath,
      @QueryParameter("fullBackupSchedule") final String fullBackupSchedule,
      @QueryParameter("diffBackupSchedule") final String diffBackupSchedule) throws IOException {
    Hudson.getInstance().checkPermission(Hudson.ADMINISTER);

    final ThinBackupPluginImpl plugin = ThinBackupPluginImpl.getInstance();
    plugin.setBackupPath(backupPath);
    plugin.setFullBackupSchedule(fullBackupSchedule);
    plugin.setDiffBackupSchedule(diffBackupSchedule);
    plugin.save();
    LOGGER.fine("Save backup settings done.");
    rsp.sendRedirect(res.getContextPath() + "/thinBackup");
  }

  public FormValidation doCheckBackupPath(final StaplerRequest res, final StaplerResponse rsp,
      @QueryParameter("backupPath") final String backupPath) {
    if ((backupPath == null) || backupPath.isEmpty()) {
      return FormValidation.error("'Backup Path' is not mandatory.");
    }

    final File backupdir = new File(backupPath);
    if (!backupdir.exists()) {
      return FormValidation.warning("The given directory does not exist, it will be created during the first run.");
    }
    if (!backupdir.isDirectory()) {
      return FormValidation.error("A file with this name already exists.");
    }
    return FormValidation.ok();
  }

  public FormValidation doCheckBackupTime(final StaplerRequest res, final StaplerResponse rsp,
      @QueryParameter("backupTime") final String backupTime) {
    String message;
    if ((backupTime != null) && !backupTime.isEmpty()) {
      try {
        message = new CronTab(backupTime).checkSanity();
      } catch (final ANTLRException e) {
        return FormValidation.error(e.getMessage());
      }
      if (message != null) {
        return FormValidation.warning(message);
      } else {
        return FormValidation.ok();
      }
    } else {
      return FormValidation.ok();
    }
  }

  public ThinBackupPluginImpl getConfiguration() {
    return ThinBackupPluginImpl.getInstance();
  }

  public List<String> getAvailableBackups() {
    final ThinBackupPluginImpl plugin = ThinBackupPluginImpl.getInstance();
    IOFileFilter filter = FileFilterUtils.prefixFileFilter(BackupType.FULL.toString());
    filter = FileFilterUtils.orFileFilter(filter, FileFilterUtils.prefixFileFilter(BackupType.DIFF.toString()));
    filter = FileFilterUtils.andFileFilter(filter, DirectoryFileFilter.DIRECTORY);
    final String[] backups = new File(plugin.getBackupPath()).list(filter);

    final List<String> list = new ArrayList<String>(backups.length);
    for (final String name : backups) {
      list.add(name.replaceFirst(String.format("(%s|%s)-", BackupType.FULL, BackupType.DIFF), ""));
    }
    Collections.sort(list);

    return list;
  }
}
