package org.glydar.paraglydar.command.manager;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.glydar.paraglydar.command.Command;
import org.glydar.paraglydar.command.CommandExecutor;
import org.glydar.paraglydar.command.CommandName;
import org.glydar.paraglydar.command.CommandOutcome;
import org.glydar.paraglydar.command.CommandSender;
import org.glydar.paraglydar.command.CommandSet;
import org.glydar.paraglydar.logging.GlydarLogger;
import org.glydar.paraglydar.plugin.Plugin;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

/**
 * This is the class that is in-charge of managing all commands.
 */
public class CommandManager {

	private static final String PERMISSION_ERROR = "Sorry, you do not have permission for this command.";
	private static final String INVALID_COMMAND = "Invalid command entered! Type /help for help!";
	private static final String ERROR_OCCURRED = "An error occurred! Please contact the server administrators.";
	private static final String UNSUPPORTED_SENDER_ERROR = "";

	private final GlydarLogger logger;
	private final Map<CommandName, RegisteredCommand> commands;

	public CommandManager(GlydarLogger logger) {
		this.logger = logger;
		this.commands = new HashMap<>();
	}

	/**
	 * Selectively registers a list of commands by name.
	 * 
	 * @param plugin The main {@link Plugin} class of the plugin that is registering this command.
	 * @param set The class implementing {@link CommandSet} containing the commands.
	 * @param name A set of strings representing the names of the commands being registered.
	 */
	public void register(Plugin plugin, CommandSet set, String... name) {
		for (Method method : set.getClass().getDeclaredMethods()) {
			if (validateMethod(method)) {
				Command annotation = method.getAnnotation(Command.class);
				if (Arrays.equals(annotation.name(), name)) {
					registerMethodCommand(plugin, set, method, annotation);
					break;
				}
			}
		}
	}

	/**
	 * Registers all commands in a class that implements CommandSet
	 * 
	 * @param plugin The main {@link Plugin} class of the plugin that is registering this command.
	 * @param set The class implementing {@link CommandSet} containing the commands.
	 */
	public void register(Plugin plugin, CommandSet set) {
		for (Method method : set.getClass().getDeclaredMethods()) {
			if (validateMethod(method)) {
				registerMethodCommand(plugin, set, method, method.getAnnotation(Command.class));
			}
		}
	}

	private boolean validateMethod(Method m) {
		Command cmdAn = m.getAnnotation(Command.class);
		if (cmdAn == null) {
			return false;
		}

		if (Modifier.isStatic(m.getModifiers())) {
			logInvalidCommandMethod(m, "is static");
			return false;
		}

		if (!Modifier.isPublic(m.getModifiers())) {
			logInvalidCommandMethod(m, "is not public");
			return false;
		}

		if (m.getReturnType() != CommandOutcome.class) {
			logInvalidCommandMethod(m, "does not return 'CommandOutcome'");
			return false;
		}

		Class<?>[] parameterTypes = m.getParameterTypes();
		if (parameterTypes.length == 0) {
			logInvalidCommandMethod(m, "does not have the required (more than 0) parameters number");
			return false;
		}

		if (!CommandSender.class.isAssignableFrom(parameterTypes[0])) {
			logInvalidCommandMethod(m, "does not have a subclass of 'CommandSender' as it's first parameter");
			return false;
		}

		// Check mandatory argument parameters
		for (int i = 1; i < parameterTypes.length - 1; i++) {
			if (parameterTypes[i] != String.class) {
				logInvalidCommandMethod(m, "does not have 'String' as a mandatory parameter at index " + i);
				return false;
			}
		}

		// Check last parameter (mandatory or rest) parameter
		if (parameterTypes.length > 1) {
			Class<?> lastParameterType = parameterTypes[parameterTypes.length - 1];
			if (lastParameterType != String.class && lastParameterType != String[].class) {
				logInvalidCommandMethod(m, "does not have 'String' or 'String[]' as it's last parameter");
				return false;
			}
		}

		return true;
	}

	private void logInvalidCommandMethod(Method method, String what) {
		logger.warning("Command Method `{0}` {1}, skipping", method, what);
	}

	private void registerMethodCommand(Plugin plugin, CommandSet instance, Method method, Command annotation) {
		MethodCommandExecutor executor = new MethodCommandExecutor(instance, method, annotation);
		register(plugin, CommandName.of(annotation.name()), executor, annotation.aliases());
	}

	public void register(Plugin plugin, CommandName name, CommandExecutor executor, String... aliases) {
		register(plugin, name.getPluginPrefixed(plugin), executor, true, false);
		register(plugin, name, executor, false, false);
		for (String aliasPart : aliases) {
			CommandName alias = name.getAlias(aliasPart);
			register(plugin, alias, executor, false, true);
		}
	}

	private void register(Plugin plugin, CommandName name, CommandExecutor executor, boolean isPluginPrefixed, boolean isAlias) {
		RegisteredCommand command = commands.get(name);
		// Check for conflict
		if (command != null) {
			if (isPluginPrefixed) {
				logger.warning("Overriding existing command with plugin prefixed one");
			}
			else {
				// New command is an alias or old one is not, skip registration and log conflict
				if (isAlias || !command.isAlias()) {
					logger.warning("Tried to register command `{0}` which is already registered", name);
					return;
				}

				// Old command is an alias and the new one is not, it will be replaced
				logger.warning("Replacing aliased command with main command {0}", name);
			}
		}

		command = new RegisteredCommand(plugin, executor, isAlias);
		commands.put(name, command);
	}

	public CommandOutcome execute(CommandSender sender, String commandLine) {
		return execute(sender, commandLine.split(" +"));
	}

	public CommandOutcome execute(CommandSender sender, String... args) {
		CommandName name = CommandName.of(args);
		while (true) {
			RegisteredCommand cmd = commands.get(name);
			if (cmd != null) {
				String[] realArgs = new String[args.length - name.size()];
				System.arraycopy(args, name.size(), realArgs, 0, realArgs.length);
				return doExecute(sender, name, cmd, realArgs);
			}

			if (name.hasParent()) {
				name = name.getParent();
			}
			else {
				break;
			}
		}

		sender.sendMessage(INVALID_COMMAND);
		return CommandOutcome.NOT_HANDLED;
	}

	public CommandOutcome execute(CommandSender sender, CommandName name, String... args) {
		for (String arg : args) {
			Preconditions.checkNotNull(arg);
			Preconditions.checkArgument(!arg.isEmpty());
		}

		RegisteredCommand cmd = commands.get(name);
		if (cmd == null) {
			sender.sendMessage(INVALID_COMMAND);
			return CommandOutcome.NOT_HANDLED;
		}

		return doExecute(sender, name, cmd, args);
	}

	private CommandOutcome doExecute(CommandSender sender, CommandName name, RegisteredCommand cmd, String... args) {
		logger.info("Handling valid command");
		CommandOutcome outcome;
		try {
			outcome = cmd.execute(sender, args);
		} catch (Exception exc) {
			outcome = CommandOutcome.ERROR;
			logger.warning(exc, "Exception thrown in Event handler");
		}

		outcome = Objects.firstNonNull(outcome, CommandOutcome.FAILURE_OTHER);
		switch (outcome) {
		case SUCCESS:
			break;
		case NO_PERMISSION:
			sender.sendMessage(PERMISSION_ERROR);
			break;
		case WRONG_USAGE:
			sender.sendMessage("/" + name + " " + cmd.getUsage());
			break;
		case UNSUPPORTED_SENDER:
			sender.sendMessage(UNSUPPORTED_SENDER_ERROR);
			break;
		case ERROR:
			sender.sendMessage(ERROR_OCCURRED);
			break;
		case NOT_HANDLED:
			// Commands shouldn't return this
			outcome = CommandOutcome.FAILURE_OTHER;
			// No break
		case FAILURE_OTHER:
			sender.sendMessage(ERROR_OCCURRED);
			break;
		}

		return outcome;
	}
}
