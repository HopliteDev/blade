package me.vaperion.blade.container.impl;

import lombok.Getter;
import me.vaperion.blade.command.BladeCommand;
import me.vaperion.blade.command.impl.BukkitUsageMessage;
import me.vaperion.blade.container.CommandContainer;
import me.vaperion.blade.container.ContainerCreator;
import me.vaperion.blade.context.BladeContext;
import me.vaperion.blade.context.impl.BukkitSender;
import me.vaperion.blade.exception.BladeExitMessage;
import me.vaperion.blade.exception.BladeUsageMessage;
import me.vaperion.blade.service.BladeCommandService;
import me.vaperion.blade.utils.Tuple;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.SimplePluginManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.stream.Collectors;

@Getter
public class BukkitCommandContainer extends Command implements CommandContainer {

    public static final ContainerCreator<BukkitCommandContainer> CREATOR = BukkitCommandContainer::new;
    private static final Field COMMAND_MAP, KNOWN_COMMANDS;
    private static final String UNKNOWN_COMMAND_MESSAGE;

    static {
        Field mapField = null, commandsField = null;
        String unknownCommandMessage = ChatColor.WHITE + "Unknown command. Type \"/help\" for help.";

        try {
            Class<?> spigotConfigClass = Class.forName("org.spigotmc.SpigotConfig");
            Field unknownCommandField = spigotConfigClass.getDeclaredField("unknownCommandMessage");

            unknownCommandField.setAccessible(true);
            unknownCommandMessage = ChatColor.WHITE + (String) unknownCommandField.get(null);
        } catch (Exception ex) {
            System.err.println("Failed to grab unknown command message from SpigotConfig.");
            ex.printStackTrace();
        }

        try {
            mapField = SimplePluginManager.class.getDeclaredField("commandMap");
            commandsField = SimpleCommandMap.class.getDeclaredField("knownCommands");

            mapField.setAccessible(true);
            commandsField.setAccessible(true);
        } catch (Exception ex) {
            System.err.println("Failed to grab commandMap from the plugin manager.");
            ex.printStackTrace();
        }

        COMMAND_MAP = mapField;
        KNOWN_COMMANDS = commandsField;
        UNKNOWN_COMMAND_MESSAGE = unknownCommandMessage;
    }

    private final BladeCommandService commandService;
    private final BladeCommand parentCommand;

    @SuppressWarnings("unchecked")
    private BukkitCommandContainer(@NotNull BladeCommandService service, @NotNull BladeCommand command, @NotNull String alias, @NotNull String fallbackPrefix) throws Exception {
        super(alias, command.getDescription(), "/" + alias, new ArrayList<>());

        this.commandService = service;
        this.parentCommand = command;

        SimplePluginManager simplePluginManager = (SimplePluginManager) Bukkit.getServer().getPluginManager();
        SimpleCommandMap simpleCommandMap = (SimpleCommandMap) COMMAND_MAP.get(simplePluginManager);

        if (service.isOverrideCommands()) {
            Map<String, Command> knownCommands = (Map<String, Command>) KNOWN_COMMANDS.get(simpleCommandMap);
            Iterator<Map.Entry<String, Command>> iterator = knownCommands.entrySet().iterator();

            while (iterator.hasNext()) {
                Map.Entry<String, Command> entry = iterator.next();
                Command registeredCommand = entry.getValue();

                if (doesBukkitCommandConflict(registeredCommand, alias, command)) {
                    registeredCommand.unregister(simpleCommandMap);
                    iterator.remove();
                }
            }
        }

        simpleCommandMap.register(fallbackPrefix, this);
    }

    private boolean doesBukkitCommandConflict(@NotNull Command bukkitCommand, @NotNull String alias, @NotNull BladeCommand bladeCommand) {
        if (bukkitCommand instanceof BukkitCommandContainer) return false; // don't override our own commands
        if (bukkitCommand.getName().equalsIgnoreCase(alias) || bukkitCommand.getAliases().stream().anyMatch(a -> a.equalsIgnoreCase(alias)))
            return true;
        for (String realAlias : bladeCommand.getRealAliases()) {
            if (bukkitCommand.getName().equalsIgnoreCase(realAlias) || bukkitCommand.getAliases().stream().anyMatch(a -> a.equalsIgnoreCase(realAlias)))
                return true;
        }
        return false;
    }

    @Nullable
    private Tuple<BladeCommand, String> resolveCommand(@NotNull String[] arguments) throws BladeExitMessage {
        return commandService.getCommandResolver().resolveCommand(arguments);
    }

    @NotNull
    private String getSenderType(@NotNull Class<?> clazz) {
        switch (clazz.getSimpleName()) {
            case "Player":
                return "players";

            case "ConsoleCommandSender":
                return "the console";

            default:
                return "everyone";
        }
    }

    private void sendUsageMessage(@NotNull BladeContext context, @Nullable BladeCommand command) {
        if (command == null) return;
        command.getUsageMessage().ensureGetOrLoad(() -> new BukkitUsageMessage(command)).sendTo(context);
    }

    private boolean hasPermission(@NotNull CommandSender sender, String[] args) throws BladeExitMessage {
        Tuple<BladeCommand, String> command = resolveCommand(joinAliasToArgs(this.parentCommand.getAliases()[0], args));
        BladeContext context = new BladeContext(commandService, new BukkitSender(sender), command == null ? "" : command.getRight(), args);
        return checkPermission(context, command == null ? null : command.getLeft()).getLeft();
    }

    private Tuple<Boolean, String> checkPermission(@NotNull BladeContext context, @Nullable BladeCommand command) throws BladeExitMessage {
        if (command == null)
            return new Tuple<>(false, "This command failed to execute as we couldn't find its registration.");

        return new Tuple<>(
              commandService.getPermissionTester().testPermission(context, command),
              command.isHidden() ? UNKNOWN_COMMAND_MESSAGE : command.getPermissionMessage());
    }

    private String[] joinAliasToArgs(String alias, String[] args) {
        String[] aliasParts = alias.split(" ");
        String[] argsWithAlias = new String[args.length + aliasParts.length];
        System.arraycopy(aliasParts, 0, argsWithAlias, 0, aliasParts.length);
        System.arraycopy(args, 0, argsWithAlias, aliasParts.length, args.length);
        return argsWithAlias;
    }

    @Override
    public boolean testPermissionSilent(@NotNull CommandSender sender) {
        return hasPermission(sender, new String[0]);
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
        BladeCommand command = null;
        String resolvedAlias = alias;

        String[] joined = joinAliasToArgs(alias, args);
        BladeContext context = new BladeContext(commandService, new BukkitSender(sender), alias, args);

        try {
            Tuple<BladeCommand, String> resolved = resolveCommand(joined);
            if (resolved == null) {
                List<BladeCommand> availableCommands = commandService.getAllBladeCommands()
                      .stream().filter(c -> Arrays.stream(c.getAliases()).anyMatch(a -> a.toLowerCase().startsWith(alias.toLowerCase(Locale.ROOT) + " ") || a.equalsIgnoreCase(alias)))
                      .filter(c -> this.checkPermission(context, c).getLeft())
                      .collect(Collectors.toList());

                for (String line : commandService.getHelpGenerator().generate(context, availableCommands)) {
                    sender.sendMessage(line);
                }

                return true;
            }

            Tuple<Boolean, String> permissionResult = checkPermission(context, resolved.getLeft());
            if (!permissionResult.getLeft()) throw new BladeExitMessage(permissionResult.getRight());

            command = resolved.getLeft();
            resolvedAlias = resolved.getRight();
            int offset = Math.min(args.length, resolvedAlias.split(" ").length - 1);

            if (command.isSenderParameter() && !command.getSenderType().isInstance(sender))
                throw new BladeExitMessage("This command can only be executed by " + getSenderType(command.getSenderType()) + ".");

            final BladeCommand finalCommand = command;
            final String finalResolvedAlias = resolvedAlias;

            if (finalCommand.getMethod() == null) {
                throw new BladeExitMessage("The command " + finalResolvedAlias + " is a root command and cannot be executed.");
            }

            Runnable runnable = () -> {
                try {
                    List<Object> parsed;
                    if (finalCommand.isContextBased()) {
                        parsed = Collections.singletonList(context);
                    } else {
                        parsed = commandService.getCommandParser().parseArguments(finalCommand, context, Arrays.copyOfRange(args, offset, args.length));
                        if (finalCommand.isSenderParameter()) parsed.add(0, sender);
                    }

                    finalCommand.getMethod().invoke(finalCommand.getInstance(), parsed.toArray(new Object[0]));
                } catch (BladeUsageMessage ex) {
                    sendUsageMessage(context, finalCommand);
                } catch (BladeExitMessage ex) {
                    sender.sendMessage(ChatColor.RED + ex.getMessage());
                } catch (InvocationTargetException ex) {
                    if (ex.getTargetException() != null) {
                        if (ex.getTargetException() instanceof BladeUsageMessage) {
                            sendUsageMessage(context, finalCommand);
                            return;
                        } else if (ex.getTargetException() instanceof BladeExitMessage) {
                            sender.sendMessage(ChatColor.RED + ex.getTargetException().getMessage());
                            return;
                        }
                    }

                    ex.printStackTrace();
                    sender.sendMessage(ChatColor.RED + "An exception was thrown while executing this command.");
                } catch (Throwable t) {
                    t.printStackTrace();
                    sender.sendMessage(ChatColor.RED + "An exception was thrown while executing this command.");
                }
            };

            if (command.isAsync()) {
                commandService.getAsyncExecutor().accept(runnable);
            } else {
                long time = System.nanoTime();
                runnable.run();
                long elapsed = (System.nanoTime() - time) / 1000000;

                if (elapsed >= commandService.getExecutionTimeWarningThreshold()) {
                    Bukkit.getLogger().warning(String.format(
                          "[Blade] Command '%s' (%s#%s) took %d milliseconds to execute!",
                          finalResolvedAlias,
                          finalCommand.getMethod().getDeclaringClass().getName(),
                          finalCommand.getMethod().getName(),
                          elapsed
                    ));
                }
            }

            return true;
        } catch (BladeUsageMessage ex) {
            sendUsageMessage(context, command);
        } catch (BladeExitMessage ex) {
            sender.sendMessage(ChatColor.RED + ex.getMessage());
        } catch (Throwable t) {
            t.printStackTrace();
            sender.sendMessage(ChatColor.RED + "An exception was thrown while executing this command.");
        }

        return false;
    }

    @NotNull
    @Override
    public List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) throws IllegalArgumentException {
        if (!commandService.getTabCompleter().isDefault()) return Collections.emptyList();
        if (!hasPermission(sender, args)) return Collections.emptyList();

        try {
            Tuple<BladeCommand, String> resolved = resolveCommand(joinAliasToArgs(alias, args));
            if (resolved == null) {
                // maybe suggest subcommands?
                return Collections.emptyList();
            }

            BladeCommand command = resolved.getLeft();
            String foundAlias = resolved.getRight();

            List<String> argList = new ArrayList<>(Arrays.asList(args));
            if (foundAlias.split(" ").length > 1) argList.subList(0, foundAlias.split(" ").length - 1).clear();

            if (argList.isEmpty()) argList.add("");
            String[] actualArguments = argList.toArray(new String[0]);

            BladeContext context = new BladeContext(commandService, new BukkitSender(sender), foundAlias, actualArguments);

            List<String> suggestions = new ArrayList<>();
            commandService.getCommandCompleter().suggest(suggestions, context, command, actualArguments);
            return suggestions;
        } catch (BladeExitMessage ex) {
            sender.sendMessage(ChatColor.RED + ex.getMessage());
        } catch (Exception ex) {
            ex.printStackTrace();
            sender.sendMessage(ChatColor.RED + "An exception was thrown while completing this command.");
        }

        return Collections.emptyList();
    }
}