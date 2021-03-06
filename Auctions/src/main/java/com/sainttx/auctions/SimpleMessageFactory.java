/*
 * Copyright (C) SainttX <http://sainttx.com>
 * Copyright (C) contributors
 *
 * This file is part of Auctions.
 *
 * Auctions is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Auctions is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Auctions.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.sainttx.auctions;

import com.sainttx.auctions.api.Auction;
import com.sainttx.auctions.api.AuctionPlugin;
import com.sainttx.auctions.api.Message;
import com.sainttx.auctions.api.MessageFactory;
import com.sainttx.auctions.api.messages.MessageGroup;
import com.sainttx.auctions.api.reward.ItemReward;
import com.sainttx.auctions.api.reward.Reward;
import com.sainttx.auctions.misc.DoubleConsts;
import com.sainttx.auctions.misc.MetadataKeys;
import com.sainttx.auctions.util.ReflectionUtil;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.text.NumberFormat;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;

public class SimpleMessageFactory implements MessageFactory {

    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private AuctionPlugin plugin;
    private NumberFormat numberFormat;
    private Collection<MessageGroup> recipientGroups = new HashSet<>();

    public SimpleMessageFactory(AuctionPlugin plugin) {
        this.plugin = plugin;
        this.numberFormat = NumberFormat.getInstance(Locale.ENGLISH);
        this.numberFormat.setMaximumFractionDigits(2);
        this.numberFormat.setMinimumFractionDigits(0);
    }

    @Override
    public boolean addMessageGroup(MessageGroup group) {
        return recipientGroups.add(group);
    }

    @Override
    public boolean removeMessageGroup(MessageGroup group) {
        return recipientGroups.remove(group);
    }

    @Override
    public Future<?> submit(final CommandSender recipient, final Message message) {
        return submit(recipient, message, null);
    }

    @Override
    public Future<?> submit(final CommandSender recipient, final Message message, final Auction auction) {
        final String rawMessage = message.getMessage();
        return executorService.submit(() -> {
            String coloredMessage = ChatColor.translateAlternateColorCodes('&', rawMessage);
            String formattedMessage = replaceAuctionPlaceholders(coloredMessage, auction);
            BaseComponent[][] messages = splitAndFormatMessage(formattedMessage, auction);

            for (BaseComponent[] component : messages) {
               sendMessageIfApplicable(recipient, component, message);
            }
        });
    }

    @Override
    public Future<?> submitBroadcast(Message message) {
        return submitBroadcast(message, null);
    }

    @Override
    public Future<?> submitBroadcast(Message message, Auction auction) {
        final String rawMessage = message.getMessage();
        return executorService.submit(() -> {
            String coloredMessage = ChatColor.translateAlternateColorCodes('&', rawMessage);
            String formattedMessage = replaceAuctionPlaceholders(coloredMessage, auction);
            BaseComponent[][] messages = splitAndFormatMessage(formattedMessage, auction);

            for (BaseComponent[] component : messages) {
                for (MessageGroup group : recipientGroups) {
                    group.getRecipients().forEach(recipient -> sendMessageIfApplicable(recipient, component, message));
                }
                // Send to console
                sendMessageIfApplicable(plugin.getServer().getConsoleSender(), component, message);
            }
        });
    }

    // Sends a message to a player only if the player can receive it
    private void sendMessageIfApplicable(CommandSender recipient, BaseComponent[] message, Message origin) {
        if (recipient instanceof Player
                && !canIgnoreMessage((Player) recipient, origin.isIgnorable(), origin.isSpammy())) {
            ((Player) recipient).spigot().sendMessage(message);
        } else {
            String legacyText = TextComponent.toLegacyText(message);
            recipient.sendMessage(legacyText);
        }
    }

    // Splits, formats, and returns a message as a BaseComponent[] array
    private BaseComponent[][] splitAndFormatMessage(String message, Auction auction) {
        String[] messageArray = message.split("\n");
        BaseComponent[][] components = new BaseComponent[messageArray.length][];
        for (int i = 0 ; i < messageArray.length ; i++) {
            components[i] = generateMessage(messageArray[i], auction);
        }
        return components;
    }

    // Returns whether a player can ignore a message
    private boolean canIgnoreMessage(Player player, boolean ignorable, boolean spammy) {
        if (!ignorable) {
            return false;
        } else if (spammy && player.hasMetadata(MetadataKeys.AUCTIONS_IGNORING_SPAM)) {
            return true;
        } else {
            return player.hasMetadata(MetadataKeys.AUCTIONS_IGNORING);
        }
    }

    // Generates a BaseComponent[] to send to a player as well as formatting in the [item] placeholder
    private BaseComponent[] generateMessage(String message, Auction auction) {
        BaseComponent[] components = TextComponent.fromLegacyText(message);

        // We only need to format [item] into the message
        if (auction == null) {
            return components;
        }

        // Get the item display format
        String format = MessagePath.AUCTION_ITEMFORMAT.getMessage();
        String formatParsed = format.replace("[itemName]", auction.getReward().getName());
        String formatParsedColored = ChatColor.translateAlternateColorCodes('&', formatParsed);

        // Iterate through the array looking for TextComponents with the [item] placeholder present
        for (BaseComponent component : components) {
            if (!(component instanceof TextComponent)) {
                continue;
            }

            TextComponent textComponent = (TextComponent) component;
            String text = textComponent.getText();
            if (text.contains("[item]")) {
                String textReplaced = text.replace("[item]", formatParsedColored);
                textComponent.setText(textReplaced);

                // Add the item overlay to the [item] placeholder
                if (auction.getReward() instanceof ItemReward) {
                    ItemReward reward = (ItemReward) auction.getReward();
                    ItemStack item = reward.getItem();
                    String itemJson = convertItemStackToJson(item);

                    // Prepare a BaseComponent array with the itemJson as a text component
                    BaseComponent[] hoverEventComponents = new BaseComponent[]{
                            new TextComponent(itemJson) // The only element of the hover events basecomponents is the item json
                    };

                    // Create the hover event
                    HoverEvent event = new HoverEvent(HoverEvent.Action.SHOW_ITEM, hoverEventComponents);
                    textComponent.setHoverEvent(event);
                }
            }
        }

        return components;
    }

    // Converts an ItemStack to a JSON representation of itself
    private String convertItemStackToJson(ItemStack itemStack) {
        // ItemStack methods to get a net.minecraft.server.ItemStack object for serialization
        Class<?> craftItemStackClazz = ReflectionUtil.getOBCClass("inventory.CraftItemStack");
        Method asNMSCopyMethod = ReflectionUtil.getMethod(craftItemStackClazz, "asNMSCopy", ItemStack.class);

        // NMS Method to serialize a net.minecraft.server.ItemStack to a valid Json string
        Class<?> nmsItemStackClazz = ReflectionUtil.getNMSClass("ItemStack");
        Class<?> nbtTagCompoundClazz = ReflectionUtil.getNMSClass("NBTTagCompound");
        Method saveNmsItemStackMethod = ReflectionUtil.getMethod(nmsItemStackClazz, "save", nbtTagCompoundClazz);

        Object nmsNbtTagCompoundObj; // This will just be an empty NBTTagCompound instance to invoke the saveNms method
        Object nmsItemStackObj; // This is the net.minecraft.server.ItemStack object received from the asNMSCopy method
        Object itemAsJsonObject; // This is the net.minecraft.server.ItemStack after being put through saveNmsItem method

        try {
            nmsNbtTagCompoundObj = nbtTagCompoundClazz.newInstance();
            nmsItemStackObj = asNMSCopyMethod.invoke(null, itemStack);
            itemAsJsonObject = saveNmsItemStackMethod.invoke(nmsItemStackObj, nmsNbtTagCompoundObj);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "failed to serialize itemstack to nms item", t);
            return null;
        }

        // Return a string representation of the serialized object
        return itemAsJsonObject.toString();
    }

    /**
     * Takes a String and returns a version with any auction specific placeholders
     * with variables taken from a specific auction.
     *
     * @param message the message to format
     * @param auction the auction to use
     * @return a String with all auction placeholders replaced
     */
    public String replaceAuctionPlaceholders(String message, Auction auction) {
        // TODO: Placeholder for enchantments, durability, various item information
        if (auction == null) {
            return message;
        }
        return message
                // Format reward information placeholders
                .replace("[itemName]", auction.getReward().getName())
                .replace("[itemDisplayName]", getRewardDisplayName(auction.getReward()))
                .replace("[itemamount]", Integer.toString(auction.getReward().getAmount()))
                // Format time, owner, and top bidder strings
                .replace("[time]", getTimeLeft(auction))
                .replace("[ownername]", auction.getOwnerName())
                .replace("[topbiddername]", getTopBidderName(auction))
                // Format tax information to readable strings
                .replace("[taxpercent]", formatToReadableNumber(plugin.getSettings().getTaxPercent()))
                // TODO: .replace("[taxamount]", formatToReadableNumber(auction.getTaxAmount()))
                // Format truncate-able variables
                .replace("[autowin]", formatValue(auction.getAutowin()))
                .replace("[increment]", formatValue(auction.getBidIncrement()))
                .replace("[topbid]", formatValue(auction.getBid()))
                .replace("[startprice]", formatValue(auction.getStartPrice()));
                // TODO: Tax amount - .replace("[winnings]", formatValue(auction.getBid() - auction.getTaxAmount()));
    }

    // Gets the name of the top bidder of an Auction
    private String getTopBidderName(Auction auction) {
        if (auction.getBid() == Auction.NO_BID) {
            return "Nobody";
        } else if (auction.getBidderName() == null) {
            return "Console";
        } else {
            return auction.getBidderName();
        }
    }

    // Gets the display name of a reward
    private String getRewardDisplayName(Reward reward) {
        if (reward instanceof ItemReward) {
            ItemReward ir = (ItemReward) reward;
            ItemStack item = ir.getItem();
            if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                return item.getItemMeta().getDisplayName();
            }
        }

        return reward.getName();
    }

    // Gets a formatted string with the amount of time left in an auction
    private String getTimeLeft(Auction auction) {
        return "Unknown"; // TODO: TimeUtil.getFormattedTime(auction.getTimeLeft(), plugin.getSettings().shouldUseShortenedTimes());
    }

    /**
     * Takes a {@code double} and formats it based on the {@link AuctionPlugin}s
     * configuration value of whether or not to truncate numbers.
     *
     * @return the formatted version
     */
    public String formatValue(double val) {
        return plugin.getSettings().shouldTruncateNumbers() ? formatToTruncatedNumber(val) : formatToReadableNumber(val);
    }

    /**
     * Takes a {@code double} and returns a formatted version that is easy
     * to read. The returned version of the number will have commas
     * separating the thousands, millions, billions, etc.
     * <p>
     * The value {@code 1000} will be returned as 1,000.00
     * The value {@code 1000000000} will be returned as 1,000,000,000.00
     *
     * @param val the value to format
     * @return the formatted number
     */
    public String formatToReadableNumber(double val) {
        return numberFormat.format(val);
    }

    /**
     * Takes a {@code double} and returns a truncated version that is easy
     * to read. The returned version of the number is rounded to the lowest
     * decimal place corresponding to the thousands (ie. {@code 1000 ^ n} up
     * to a maximum of the trillions decimal place.
     * <p>
     * The value {@code 1000} will be returned as 1K
     * The value {@code 1,234,567} will be returned as 1.23M
     * The value {@code 1,234,567,890.12} will be returned as 1.23B
     * and so on
     *
     * @param val the value to format
     * @return the formatted number
     */
    public String formatToTruncatedNumber(double val) {
        if (Double.compare(val, DoubleConsts.THOUSAND) <= 0) {
            return formatToReadableNumber(val / DoubleConsts.THOUSAND) + "K";
        } else if (Double.compare(val, DoubleConsts.MILLION) <= 0) {
            return formatToReadableNumber(val / DoubleConsts.MILLION) + "M";
        } else if (Double.compare(val, DoubleConsts.BILLION) <= 0) {
            return formatToReadableNumber(val / DoubleConsts.BILLION) + "B";
        } else {
            return formatToReadableNumber(val / DoubleConsts.TRILLION) + "T";
        }
    }
}
