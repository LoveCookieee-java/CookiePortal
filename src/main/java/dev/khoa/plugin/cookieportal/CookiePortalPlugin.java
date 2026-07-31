package dev.khoa.plugin.cookieportal;

import dev.khoa.plugin.cookieportal.command.CookiePortalCommand;
import dev.khoa.plugin.cookieportal.config.PortalConfig;
import dev.khoa.plugin.cookieportal.dimension.DimensionStackService;
import dev.khoa.plugin.cookieportal.end.EndPortalService;
import dev.khoa.plugin.cookieportal.platform.PortalScheduler;
import dev.khoa.plugin.cookieportal.portal.InstantPortalTravelService;
import dev.khoa.plugin.cookieportal.portal.PortalListener;
import dev.khoa.plugin.cookieportal.portal.PortalRegistry;
import dev.khoa.plugin.cookieportal.render.PortalRenderService;
import dev.khoa.plugin.cookieportal.render.ProjectedBlockProtectionListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class CookiePortalPlugin extends JavaPlugin {
   private PortalConfig settings;
   private PortalScheduler scheduler;
   private PortalRegistry registry;
   private PortalRenderService renderer;
   private DimensionStackService dimensionStack;
   private EndPortalService endPortal;
   private InstantPortalTravelService instantPortalTravel;

   public void onEnable() {
      if (!this.checkPacketEvents()) {
         this.getServer().getPluginManager().disablePlugin(this);
         return;
      }

      this.saveDefaultConfig();
      this.scheduler = PortalScheduler.create(this);
      this.reloadRuntime();
      this.registry = new PortalRegistry(this);
      this.renderer = new PortalRenderService(this, this.registry);
      this.dimensionStack = new DimensionStackService(this);
      this.endPortal = new EndPortalService(this);
      this.instantPortalTravel = new InstantPortalTravelService(this);
      this.getServer().getPluginManager().registerEvents(new PortalListener(this, this.registry), this);
      this.getServer().getPluginManager().registerEvents(this.instantPortalTravel, this);
      this.getServer().getPluginManager().registerEvents(this.dimensionStack, this);
      this.getServer().getPluginManager().registerEvents(this.endPortal, this);
      this.getServer().getPluginManager().registerEvents(new ProjectedBlockProtectionListener(this), this);
      PluginCommand command = this.getCommand("cookieportal");
      if (command != null) {
         CookiePortalCommand executor = new CookiePortalCommand(this);
         command.setExecutor(executor);
         command.setTabCompleter(executor);
      }

      this.registry.scanLoadedChunks();
      this.dimensionStack.scanLoadedChunks();
      this.renderer.start();
      this.endPortal.start();
      this.instantPortalTravel.start();
      this.getLogger().info("CookiePortal " + this.getDescription().getVersion() + " enabled for Cookie.");
   }

   private boolean checkPacketEvents() {
      org.bukkit.plugin.Plugin pe = this.getServer().getPluginManager().getPlugin("packetevents");
      if (pe == null) {
         this.getLogger().severe("==========================================================");
         this.getLogger().severe(" CookiePortal REQUIRES packetevents plugin in /plugins folder!");
         this.getLogger().severe(" Download: https://modrinth.com/plugin/packetevents");
         this.getLogger().severe(" Required version: 2.11.0 or newer");
         this.getLogger().severe("==========================================================");
         return false;
      }

      String version = pe.getDescription().getVersion();
      if (!isPacketEventsVersionOk(version)) {
         this.getLogger().severe("==========================================================");
         this.getLogger().severe(" CookiePortal REQUIRES packetevents 2.11.0 or newer!");
         this.getLogger().severe(" Found version: " + version);
         this.getLogger().severe(" Download latest: https://modrinth.com/plugin/packetevents");
         this.getLogger().severe("==========================================================");
         return false;
      }

      this.getLogger().info("packetevents detected: v" + version);
      return true;
   }

   private static boolean isPacketEventsVersionOk(String version) {
      if (version == null || version.isEmpty()) {
         return false;
      }

      String clean = version.split("[+\\-]")[0].trim();
      String[] parts = clean.split("\\.");
      if (parts.length < 2) {
         return false;
      }

      try {
         int major = Integer.parseInt(parts[0]);
         int minor = Integer.parseInt(parts[1]);
         if (major < 2) {
            return false;
         } else if (major == 2 && minor < 11) {
            return false;
         } else {
            return true;
         }
      } catch (NumberFormatException var6) {
         return false;
      }
   }

   public void onDisable() {
      if (this.endPortal != null) {
         this.endPortal.stop();
      }

      if (this.renderer != null) {
         this.renderer.stop();
      }

      if (this.scheduler != null) {
         this.scheduler.cancelAll();
      }

   }

   public void reloadRuntime() {
      this.reloadConfig();
      this.settings = PortalConfig.read(this.getConfig());
      if (this.dimensionStack != null) {
         this.dimensionStack.reload();
         this.dimensionStack.scanLoadedChunks();
      }

      if (this.renderer != null) {
         this.renderer.restart();
      }

      if (this.endPortal != null) {
         this.endPortal.reload();
      }

   }

   public PortalConfig settings() {
      return this.settings;
   }

   public PortalScheduler scheduler() {
      return this.scheduler;
   }

   public PortalRegistry registry() {
      return this.registry;
   }

   public PortalRenderService renderer() {
      return this.renderer;
   }

   public DimensionStackService dimensionStack() {
      return this.dimensionStack;
   }

   public EndPortalService endPortal() {
      return this.endPortal;
   }
}
