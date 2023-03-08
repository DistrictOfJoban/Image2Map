package space.essem.image2map;

import net.fabricmc.api.ModInitializer;
import net.minecraft.text.HoverEvent;
import space.essem.image2map.config.Image2MapConfig;
import space.essem.image2map.renderer.MapRenderer;

import java.awt.*;
import java.io.File;
import java.net.URL;
import java.net.URLConnection;
import java.util.concurrent.CompletableFuture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.concurrent.TimeUnit;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import me.sargunvohra.mcmods.autoconfig1u.AutoConfig;
import me.sargunvohra.mcmods.autoconfig1u.serializer.GsonConfigSerializer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.LiteralText;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;

public class Image2Map implements ModInitializer {
    public static Image2MapConfig CONFIG = AutoConfig.register(Image2MapConfig.class, GsonConfigSerializer::new)
            .getConfig();

    @Override
    public void onInitialize() {
        System.out.println("Loading Image2Map...");

        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            dispatcher.register(CommandManager.literal("mapcreate")
                    .requires(source -> source.hasPermissionLevel(CONFIG.minPermLevel))
                    .then(CommandManager.argument("mode", StringArgumentType.word()).suggests(new DitherModeSuggestionProvider())
                            .then(CommandManager.argument("scalemode", StringArgumentType.word()).suggests(new ScaleModeSuggestionProvider())
                                    .then(CommandManager.argument("path", StringArgumentType.greedyString())
                                    .executes(this::createMap)))));
        });
    }

    class DitherModeSuggestionProvider implements SuggestionProvider<ServerCommandSource> {

        @Override
        public CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context,
                SuggestionsBuilder builder) throws CommandSyntaxException {
            builder.suggest("none");
            builder.suggest("dither");
            return builder.buildFuture();
        }
        
    }

    class ScaleModeSuggestionProvider implements SuggestionProvider<ServerCommandSource> {

        @Override
        public CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context,
                                                             SuggestionsBuilder builder) throws CommandSyntaxException {
            builder.suggest("resizeImage");
            builder.suggest("sameAsSource");
            return builder.buildFuture();
        }

    }

    public enum DitherMode {
        NONE,
        FLOYD;

        public static DitherMode fromString(String string) {
            if (string.equalsIgnoreCase("NONE"))
                return DitherMode.NONE;
            else if (string.equalsIgnoreCase("DITHER") || string.equalsIgnoreCase("FLOYD"))
                    return DitherMode.FLOYD;
            throw new IllegalArgumentException("invalid dither mode");
        }
    }

    private int createMap(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        Vec3d pos = source.getPosition();
        PlayerEntity player = source.getPlayer();
        DitherMode mode;
        String modeStr = StringArgumentType.getString(context, "mode");
        String scaleMode = StringArgumentType.getString(context, "scalemode");
        try {
            mode = DitherMode.fromString(modeStr);
        } catch (IllegalArgumentException e) {
            throw new SimpleCommandExceptionType(() -> "Invalid dither mode '" + modeStr + "'").create();
        }
        String input = StringArgumentType.getString(context, "path");

        source.sendFeedback(new LiteralText("Generating image map..."), false);

        getImage(input).orTimeout(60, TimeUnit.SECONDS).handleAsync((image, ex) -> {
            if(image == null || ex != null) {
                if(ex != null) {
                    source.sendFeedback(new LiteralText("That doesn't seem to be a valid image.").styled(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new LiteralText(ex.getMessage() + "A")))), false);
                } else {
                    source.sendFeedback(new LiteralText("That doesn't seem to be a valid image."), false);
                }
                return 0;
            }

            if(scaleMode.equals("resizeImage")) {
                ItemStack stack = MapRenderer.render(image, mode, source.getWorld(), pos.x, pos.z, player);

                if (!player.getInventory().insertStack(stack)) {
                    ItemEntity itemEntity = new ItemEntity(player.world, player.getPos().x, player.getPos().y,
                            player.getPos().z, stack);
                    player.world.spawnEntity(itemEntity);
                }
            } else {
                //Literal, may need split img
                // Round resolution to the nearest 128 so we don't scale the img, then we create a new image, put the old one on top of it
                BufferedImage newImage = new BufferedImage(getNearest(image.getWidth(), 128), getNearest(image.getHeight(), 128), BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = newImage.createGraphics();
                graphics.drawImage(image, 0, 0, null);
                graphics.dispose();

                int portionX = (int)Math.ceil(image.getWidth() / 128.0);
                int portionY = (int)Math.ceil(image.getHeight() / 128.0);
                for(int y = 0; y < portionY; y++) {
                    for(int x = 0; x < portionX; x++) {
                        BufferedImage croppedImage = newImage.getSubimage(x * 128, y * 128, 128, 128);
                        ItemStack stack = MapRenderer.render(croppedImage, mode, source.getWorld(), pos.x, pos.z, player);
                        stack.setCustomName(new LiteralText("X:" + (x+1) + " Y:" + (y+1)));
                        if (!player.getInventory().insertStack(stack)) {
                            ItemEntity itemEntity = new ItemEntity(player.world, player.getPos().x, player.getPos().y,
                                    player.getPos().z, stack);
                            player.world.spawnEntity(itemEntity);
                        }
                    }
                }
            }

            source.sendFeedback(new LiteralText("Done!"), false);
            return 1;
        });

        return 1;
    }

    private int getNearest(double value, int nearest) {
        // Enforce nearest as min!
        return (int)((value + value - 1) / nearest * nearest);
    }

    private CompletableFuture<BufferedImage> getImage(String input) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (isValid(input)) {
                    URL url = new URL(input);
                    URLConnection connection = url.openConnection();
                    connection.setRequestProperty("User-Agent", "Image2Map mod");
                    connection.connect();
                    return ImageIO.read(connection.getInputStream());
                } else if (CONFIG.allowLocalFiles) {
                    File file = new File(input);
                    return ImageIO.read(file);
                } else {
                    return null;
                }
            } catch (Throwable e) {
                return null;
            }
        });
    }

    private static boolean isValid(String url) {
        try {
            new URL(url).toURI();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
