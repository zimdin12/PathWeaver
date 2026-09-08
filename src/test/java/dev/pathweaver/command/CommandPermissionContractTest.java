package dev.pathweaver.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Which subcommands are gated, asserted against the tree the mod actually registers.
 *
 * <p>The defect this exists for: `/pathweaver mobs` constructs every registered entity type
 * synchronously, a cost the handler itself documents at 213 ms, and it shipped in 0.7.0 and 0.8.0
 * with no permission check at all. The source comment said it kept one and the 0.7.0 changelog said
 * the same. There was no {@code requires} anywhere in the file. Any player on any server could
 * trigger that work repeatedly.
 *
 * <p>So this walks the real registered tree rather than reading the source. A comment asserting a
 * guard is not a guard, which is the entire lesson of the finding.
 *
 * <p>What this test does NOT establish, deliberately, because overstating it would repeat the
 * original mistake: it proves the {@code mobs} node carries a requirement distinct from the
 * permissive default, and that {@code status} does not. It does not exercise a real player against
 * a running server. That witness needs a live {@code CommandSourceStack}, which needs a server, and
 * it lives in the game test of the same name.
 */
class CommandPermissionContractTest {

    /** A node from the tree the mod really registers. {@code __root__} is the pathweaver literal. */
    private static CommandNode<CommandSourceStack> node(String child) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        PathWeaverCommand.register(dispatcher);
        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("pathweaver");
        assertNotNull(root, "the pathweaver root command is not registered at all");
        if ("__root__".equals(child)) return root;
        CommandNode<CommandSourceStack> found = root.getChild(child);
        assertNotNull(found, "subcommand not registered: " + child);
        return found;
    }

    /**
     * The expensive subcommand must not be reachable on the permissive default requirement.
     *
     * <p>Brigadier gives every node {@code s -> true} unless a builder sets one, and it is the same
     * instance for every node that never called {@code requires}. Comparing against the requirement
     * of a node known to be ungated is therefore a direct test of "this one was configured", and it
     * fails the moment the {@code requires} call is removed.
     */
    @Test
    void theExpensiveSubcommandCarriesARequirementAndTheCheapOneDoesNot() {
        CommandNode<CommandSourceStack> mobs = node("mobs");
        CommandNode<CommandSourceStack> status = node("status");

        assertNotSame(status.getRequirement(), mobs.getRequirement(),
            "mobs still carries the same permissive default requirement as status, so it is ungated");
    }

    /**
     * The cheap subcommand must stay open. It is the one the README tells players to run, and
     * putting a level on it once locked every singleplayer player without cheats out of finding
     * out whether the mod was doing anything.
     */
    @Test
    void statusRemainsReachableWithoutPermission() {
        // Compared against the pathweaver literal itself, which is deliberately ungated and is built
        // through the same ArgumentBuilder path. Brigadier's default requirement is a non-capturing
        // `s -> true`, so every builder that never calls requires() shares one instance and identity
        // is a real test of "nothing was configured here".
        //
        // The first version of this compared against a fresh RootCommandNode and failed: a root's
        // default lambda is a different class from a builder's. Both are permissive, so the test was
        // asserting something true about identity that had nothing to do with permission.
        assertSame(node("__root__").getRequirement(), node("status").getRequirement(),
            "status has acquired a requirement of its own; it is meant to stay public");
    }
}
