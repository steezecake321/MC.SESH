import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line tools for the person who owns this PC. They work directly on the account files, so they do not need the manager to be running
 * and they do not need anyone's password. Run them through Owner.bat.
 */
final class Owner {
    private Owner() { }

    private static BufferedReader STDIN;

    private static String ask(String prompt) throws Exception {
        java.io.Console c = System.console();
        if (c != null && c.isTerminal()) {
            char[] pw = c.readPassword(prompt);
            return pw == null ? "" : new String(pw);
        }
        System.out.print(prompt);
        System.out.flush();
        if (STDIN == null) STDIN = new BufferedReader(new InputStreamReader(System.in));
        String l = STDIN.readLine();
        return l == null ? "" : l;
    }

    private static Users.Acct need(String[] a) {
        if (a.length < 3) throw new IllegalArgumentException("Give the username, e.g. Owner.bat " + a[1] + " alice");
        Users.Acct u = Users.byName(a[2]);
        if (u == null) throw new IllegalArgumentException("No account named '" + a[2] + "'");
        return u;
    }

    static void run(String[] args) throws Exception {
        String[] a = args;
        if (a[0].equals("--reset-password")) a = new String[]{"--owner", "wipe-all"};
        String cmd = a.length > 1 ? a[1] : "help";
        try {
            switch (cmd) {
                case "list" -> {
                    for (Users.Acct u : Users.all()) {
                        System.out.println((u.admin ? "[OP5 administrator] " : "[account]          ") + u.username + (u.disabled ? "  (disabled)" : "") + (u.grants.isEmpty() ? "" : "  access: " + u.grants));
                    }
                    if (!Users.any()) System.out.println("No accounts yet. Open the manager on this PC to create the first administrator.");
                }
                case "reset-password" -> {
                    Users.Acct u = need(a);
                    String p1 = ask("New password for " + u.username + ": ");
                    String p2 = ask("Repeat it: ");
                    if (!p1.equals(p2)) throw new IllegalArgumentException("The passwords do not match");
                    Users.changePassword(u, p1);
                    Users.audit("owner-cli", "local", "password reset by machine owner", "", u.username);
                    System.out.println("Password changed. " + u.username + " has been signed out everywhere.");
                }
                case "create-admin" -> {
                    if (a.length < 3) throw new IllegalArgumentException("Give the new username, e.g. Owner.bat create-admin alice");
                    String p1 = ask("Password for " + a[2] + ": ");
                    String p2 = ask("Repeat it: ");
                    if (!p1.equals(p2)) throw new IllegalArgumentException("The passwords do not match");
                    Users.createAdmin(a[2], p1, "machine owner (owner tools)");
                    Users.audit("owner-cli", "local", "administrator created by machine owner", "", a[2]);
                    System.out.println("Administrator account " + a[2] + " created.");
                }
                case "make-admin", "remove-admin" -> {
                    Users.Acct u = need(a);
                    boolean make = cmd.equals("make-admin");
                    if (!make && u.admin && Users.adminCount() < 2) throw new IllegalArgumentException("That is the only administrator. Create another one first.");
                    Users.setAdmin(u, make);
                    Users.audit("owner-cli", "local", make ? "administrator granted by machine owner" : "administrator removed by machine owner", "", u.username);
                    System.out.println(u.username + (make ? " is now an administrator (OP5)." : " is no longer an administrator."));
                }
                case "disable-2fa" -> {
                    Users.Acct u = need(a);
                    Users.disable2fa(u);
                    Users.audit("owner-cli", "local", "two-step sign-in turned off by machine owner", "", u.username);
                    System.out.println("Two-step sign-in is off for " + u.username + ".");
                }
                case "disable", "enable" -> {
                    Users.Acct u = need(a);
                    Users.setDisabled(u, cmd.equals("disable"));
                    Users.audit("owner-cli", "local", "account " + cmd + "d by machine owner", "", u.username);
                    System.out.println(u.username + (cmd.equals("disable") ? " is disabled." : " is enabled."));
                }
                case "delete" -> {
                    Users.Acct u = need(a);
                    if (u.admin && Users.adminCount() < 2) throw new IllegalArgumentException("That is the only administrator. Create another one first, or use wipe-all to start over.");
                    Users.delete(u);
                    Users.audit("owner-cli", "local", "account deleted by machine owner", "", u.username);
                    System.out.println(u.username + " deleted.");
                }
                case "wipe-all" -> {
                    String sure = ask("This deletes ALL accounts and invites; the next person to open the manager on this PC creates a new administrator. Type YES to continue: ");
                    if (!sure.equals("YES")) { System.out.println("Cancelled."); return; }
                    for (String sub : new String[]{"accounts", "invites"}) {
                        Path d = Main.DATA.resolve(sub);
                        if (!Files.isDirectory(d)) continue;
                        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
                            for (Path p : ds) Files.deleteIfExists(p);
                        }
                    }
                    Main.cfg.auth().clear();
                    Main.cfg.save();
                    Users.audit("owner-cli", "local", "all accounts wiped by machine owner", "", "");
                    System.out.println("All accounts removed. Open the manager on this PC to create a new administrator.");
                }
                default -> {
                    System.out.println("Owner tools (run through Owner.bat):");
                    System.out.println("  list                       show all accounts");
                    System.out.println("  reset-password <user>      set a new password (also signs that user out)");
                    System.out.println("  create-admin <user>        create an administrator (OP5) account");
                    System.out.println("  make-admin <user>          make an account an administrator");
                    System.out.println("  remove-admin <user>        take administrator rights away");
                    System.out.println("  disable <user> / enable <user>");
                    System.out.println("  disable-2fa <user>         turn off two-step sign-in (lost phone)");
                    System.out.println("  delete <user>              delete an account");
                    System.out.println("  wipe-all                   delete every account and start over");
                    System.out.println("You can also delete a file in data\\accounts or data\\invites yourself.");
                }
            }
        } catch (IllegalArgumentException | Api e) {
            System.out.println("Error: " + e.getMessage());
        }
    }
}
