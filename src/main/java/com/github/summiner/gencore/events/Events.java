package com.github.summiner.gencore.events;

import com.github.summiner.gencore.GenCore;
import com.github.summiner.gencore.handler.PluginHandler;
import com.github.summiner.gencore.data.DataBase;
import com.github.summiner.gencore.gens.GenTank;
import com.github.summiner.gencore.gens.Generator;
import com.github.summiner.gencore.gui.GenCoreGUI;
import com.github.summiner.gencore.util.ItemUtil;
import com.github.summiner.gencore.util.SoundUtil;
import de.tr7zw.changeme.nbtapi.NBTBlock;
import de.tr7zw.changeme.nbtapi.NBTItem;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.Configuration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.json.simple.parser.ParseException;

import java.text.NumberFormat;
import java.util.*;

public class Events implements Listener {

    public static HashMap<Player, Integer> slots_gens = new HashMap<>();
    public static HashMap<Player, Integer> placed_gens = new HashMap<>();
    public static HashMap<Player, HashMap<Material, ArrayList<Location>>> active_gens = new HashMap<>();
    public static HashMap<UUID, GenTank> tanks = new HashMap<>();

    NumberFormat numFormat = NumberFormat.getInstance(new Locale("en", "US"));

    static Configuration config = PluginHandler.getPlugin().getConfig();

    final String message_place_success = config.getString("messages.placegeneratorsuccess");
    final String message_place_max = config.getString("messages.placedgeneratormax");
    final String message_pickup = config.getString("messages.pickedupgenerator");
    final String message_upgrade_success = config.getString("messages.upgradegeneratorsuccess");
    final String message_upgrade_broke = config.getString("messages.upgradeneedmoney");
    final String message_upgrade_maxed = config.getString("messages.maxgenerator");
    final String message_not_yours = config.getString("messages.notyourgenerator");
    final String message_genslots_capped = config.getString("messages.maxgenslots");
    final SoundUtil.Sound sound_place = SoundUtil.fromConfigSection(config, "sounds.place");
    final SoundUtil.Sound sound_pickup = SoundUtil.fromConfigSection(config, "sounds.pickup");
    final SoundUtil.Sound sound_upgrade = SoundUtil.fromConfigSection(config, "sounds.upgrade");
    final SoundUtil.Sound sound_error = SoundUtil.fromConfigSection(config, "sounds.error");

    final Integer genslots_cap = config.getInt("genslotcap");
    public static final Integer genslots_default = config.getInt("defaultslots");
    static final Boolean tanks_enabled = config.getBoolean("tanks.enabled");

    public static void runJoin(Player player) {
        tanks.put(player.getUniqueId(), new GenTank(player.getUniqueId()));
        String[] query = DataBase.queryPlayer(player);
        if (query == null) {
            DataBase.savePlayerData(player, true);
            query = DataBase.queryPlayer(player);
        }
        if (query != null) {
            final String[] q = query;
            slots_gens.remove(player);
            slots_gens.put(player, Integer.valueOf(query[1]));
            placed_gens.remove(player);
            placed_gens.put(player, Integer.valueOf(query[2]));
            if(placed_gens.get(player) < 0) placed_gens.replace(player, 0);
            if(tanks_enabled) tanks.get(player.getUniqueId()).importSavingItems(query[4]);
            if(placed_gens.get(player) < 0) placed_gens.replace(player, 0);
            if (active_gens.get(player) == null) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        try {
                            active_gens.put(player, EventManager.getData(q[3], player));
                        } catch (ParseException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }.runTaskAsynchronously(PluginHandler.getPlugin());
            }
        }
    }
    
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        runJoin(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        new BukkitRunnable() {
            @Override
            public void run() {
                DataBase.savePlayerData(event.getPlayer(), true);
            }
        }.runTaskAsynchronously(PluginHandler.getPlugin());
    }

    public boolean isGenerator(Block block) {
        NBTBlock nbt = new NBTBlock(block);
        if (nbt.getData().getCompound("GenCore") == null) return false;
        if (nbt.getData().getCompound("GenCore").getBoolean("isGen") == null) return false;

        return true;
    }


    @EventHandler
    public void GenInteract(PlayerInteractEvent event) {
        ItemStack tool;
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            try {
                Material block = Objects.requireNonNull(event.getClickedBlock()).getType();
                if (PluginHandler.getPlugin().generatorData.containsKey(block)) {
                    HashMap<Material, ArrayList<Location>> a = active_gens.get(event.getPlayer());
                    if (!isGenerator(event.getClickedBlock())) return;
                    if (a == null) {
                        event.setCancelled(true);
                        assert message_not_yours != null;
                        event.getPlayer().spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', message_not_yours)));
                        SoundUtil.playSound(event.getPlayer(), sound_error);
                        return;
                    }
                    ArrayList<Location> b = a.get(block);
                    if (b != null && b.contains(event.getClickedBlock().getLocation())) {
                        event.setCancelled(true);
                        event.getClickedBlock().setType(Material.AIR);
                        placed_gens.replace(event.getPlayer(), EventManager.getPlaced(event.getPlayer()) - 1);
                        PluginHandler.getPlugin().giveGenerator(event.getPlayer(), block);
                        b.remove(event.getClickedBlock().getLocation());
                        assert message_pickup != null;
                        event.getPlayer().spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', message_pickup.replaceAll("\\{placed}", placed_gens.get(event.getPlayer()).toString()).replaceAll("\\{max}", slots_gens.get(event.getPlayer()).toString()))));
                        SoundUtil.playSound(event.getPlayer(), sound_pickup);
                    } else {
                        event.setCancelled(true);
                        assert message_not_yours != null;
                        event.getPlayer().spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', message_not_yours)));
                        SoundUtil.playSound(event.getPlayer(), sound_error);
                    }
                }
            } catch (NullPointerException var14) {
                event.setCancelled(true);
            }
        } else if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if(event.getClickedBlock() != null) {
                if(event.getClickedBlock().getType() == Material.LECTERN) {
                    event.setCancelled(true);
                    return;
                }
            }

            Player player = event.getPlayer();
            if (event.getHand() == EquipmentSlot.OFF_HAND) {
                return;
            }

            tool = player.getInventory().getItemInMainHand();
            if (tool.getType() == Material.PAPER) {
                ItemMeta m = tool.getItemMeta();
                if (m == null) {
                    return;
                }
                if (m.getDisplayName().equals(ChatColor.translateAlternateColorCodes('&', "&9Gen Slots Voucher &7(Right-Click)"))) {
                    int num = m.getEnchantLevel(Enchantment.LURE);
                    if(genslots_cap > 0 && (slots_gens.get(player) + num) > genslots_cap) {
                        assert message_genslots_capped != null;
                        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', message_genslots_capped)));
                        SoundUtil.playSound(event.getPlayer(), sound_error);
                    } else if (num >= 1) {
                        num += EventManager.getSlots(player);
                        slots_gens.replace(player, num);
                        tool.setAmount(tool.getAmount() - 1);
                    }
                }
            }
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                if (event.getPlayer().isSneaking()) {
                    Block block = event.getClickedBlock();
                    if (PluginHandler.getPlugin().generatorData.containsKey(block.getType())) {
                        if (!isGenerator(event.getClickedBlock())) return;
                        try {
                            Generator gen = PluginHandler.getPlugin().generatorData.get(block.getType());
                            HashMap<Material, ArrayList<Location>> c = active_gens.get(event.getPlayer());
                            ArrayList<Location> a = c.get(block.getType());
                            if (a != null && a.contains(block.getLocation())) {
                                if(gen.getNextBlock() == null) {
                                    assert message_upgrade_maxed != null;
                                    event.getPlayer().spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', message_upgrade_maxed)));
                                    SoundUtil.playSound(event.getPlayer(), sound_error);
                                    return;
                                }
                                double needed = gen.getUpgradeCost();
                                if (PluginHandler.getPlugin().checkRemoveMoney(event.getPlayer(), needed)) {
                                    a.remove(block.getLocation());
                                    block.setType(gen.getNextBlock());
                                    c.putIfAbsent(block.getType(), new ArrayList<>());
                                    a = c.get(block.getType());
                                    a.add(block.getLocation());
                                    assert message_upgrade_success != null;
                                    event.getPlayer().spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', message_upgrade_success)));
                                    SoundUtil.playSound(event.getPlayer(), sound_upgrade);
                                } else {
                                    double left = needed - PluginHandler.getPlugin().economy.getBalance(event.getPlayer());
                                    assert message_upgrade_broke != null;
                                    event.getPlayer().spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', message_upgrade_broke.replace("{amount}", numFormat.format(Math.round(left))))));
                                    SoundUtil.playSound(event.getPlayer(), sound_error);
                                }
                            } else {
                                assert message_not_yours != null;
                                event.getPlayer().spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', message_not_yours)));
                                SoundUtil.playSound(event.getPlayer(), sound_error);
                            }
                        } catch (NullPointerException e) {
                            Bukkit.getLogger().severe(String.valueOf(e));
                            assert message_not_yours != null;
                            event.getPlayer().spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', message_not_yours)));
                            SoundUtil.playSound(event.getPlayer(), sound_error);
                        }
                    }
                }
                if(event.getClickedBlock().getType() == ItemUtil.getItem("tank").getType()) {
                    event.setCancelled(true);
                    GenCoreGUI.openTankGui(event.getPlayer());
                }
            }
        }

    }

    @EventHandler
    public void blockBreak(BlockBreakEvent event) {
        if (ItemUtil.getItem("tank").getType() == event.getBlock().getType()) {
            event.setDropItems(false);
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!event.isCancelled()) {
                        event.getBlock().setType(Material.AIR);
                        event.getPlayer().getInventory().addItem(ItemUtil.getItem("tank"));
                    }
                }
            }.runTaskLater(PluginHandler.getPlugin(), 1L);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void blockPlace(BlockPlaceEvent event) {
        if (PluginHandler.getPlugin().generatorData.containsKey(event.getBlock().getType())) {
            Player player = event.getPlayer();
            ItemStack tool = ItemUtil.getItemInHand(player);
            NBTItem nbt = new NBTItem(tool);
            if (!nbt.getBoolean("isGen")) {
                event.isCancelled();
                return;
            };
            int slots = EventManager.getSlots(player);
            final int placed = EventManager.getPlaced(player) + 1;
            if (placed <= slots || GenCore.getPlugin(GenCore.class).gensSlotsEnabled) {
                if (!event.isCancelled()) {
                    if (event.getBlock().getWorld() != Bukkit.getWorld(Objects.requireNonNull(PluginHandler.getPlugin().getConfig().get("genworld")).toString())) event.setCancelled(true);
                    else if (!tool.getItemMeta().getDisplayName().equals(Objects.requireNonNull(PluginHandler.getPlugin().generatorData.get(event.getBlock().getType()).getItem().getItemMeta()).getDisplayName())) event.setCancelled(true);
                    else {
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                if(!event.isCancelled()) {
                                    placed_gens.replace(player, placed);
                                    active_gens.putIfAbsent(player, new HashMap<>());
                                    HashMap<Material, ArrayList<Location>> a = active_gens.get(player);
                                    a.putIfAbsent(event.getBlock().getType(), new ArrayList<>());
                                    ArrayList<Location> b = a.get(event.getBlock().getType());
                                    b.add(event.getBlock().getLocation());
                                    a.replace(event.getBlock().getType(), b);
                                    active_gens.replace(player, a);
                                    assert message_place_success != null;
                                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', message_place_success.replace("{placed}", String.valueOf(placed)).replace("{max}", String.valueOf(slots)))));
                                    SoundUtil.playSound(event.getPlayer(), sound_place);

                                    var nbtblock = new NBTBlock(event.getBlock());
                                    var compound = nbtblock.getData().getOrCreateCompound("GenCore");
                                    compound.setBoolean("isGen", true);
                                }
                            }
                        }.runTaskLater(PluginHandler.getPlugin(), 1L);

                    }
                }
            } else {
                event.setCancelled(true);
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', message_place_max.replace("{placed}", String.valueOf(placed - 1)).replace("{max}", String.valueOf(slots)))));
                SoundUtil.playSound(event.getPlayer(), sound_error);
            }
        }
    }

}
