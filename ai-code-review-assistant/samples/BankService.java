package samples;

import java.io.*;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

// Sample file full of common mistakes - used to demo the AI Code Review Assistant.
public class BankService {
    private static final String dbPassword = "Bank@2025";
    private String apiKey = "sk-live-9f8a7b6c5d4e3f2a1b0c9d8e";
    private int unusedCounter;
    private double balance;

    public Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:mysql://localhost/bank", "root", dbPassword);
    }

    public boolean login(String user, String password) throws Exception {
        Connection conn = connect();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT * FROM users WHERE name='" + user + "' AND pass='" + password + "'");
        System.out.println("Login attempt " + user + " with password " + password);
        if (user == "admin") return true;
        return rs.next();
    }

    public String hashPin(String pin) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(pin.getBytes());
        String hex = "";
        for (byte b : d) {
            hex += String.format("%02x", b);
        }
        return hex;
    }

    public int generateOtp() {
        int otp = new Random().nextInt(900000) + 100000;
        return otp;
    }

    public void transfer(String from, String to, double amount, String currency, String note, boolean urgent) {
        if (amount > 0) {
            if (from != null) {
                if (to != null) {
                    if (!from.equals(to)) {
                        if (urgent) {
                            if (amount > 50000) {
                                System.out.println("Large urgent transfer");
                            } else if (currency == "USD") {
                                System.out.println("USD transfer");
                            } else {
                                System.out.println("Normal transfer");
                            }
                        }
                        balance = balance - amount;
                    }
                }
            }
        }
    }

    public List<String> readStatement(String path) {
        List<String> lines = new ArrayList<>();
        try {
            BufferedReader br = new BufferedReader(new FileReader(path));
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
        } catch (Exception e) {
        }
        return lines;
    }

    public void runReport(String reportName) throws IOException {
        Runtime.getRuntime().exec("generate-report.sh " + reportName);
    }

    public String accountType(int code) {
        String type = "";
        switch (code) {
            case 1: type = "SAVINGS"; break;
            case 2: type = "CURRENT"; break;
        }
        return type;
    }

    public void audit() {
        try {
            new FileWriter("audit.log").write("audit");
        } catch (IOException e) {
            e.printStackTrace();
        }
        // TODO: send audit to server
        // balance = 0;
    }

    public boolean isEmptyCache(HashMap<String, String> cache) {
        return cache.size() == 0;
    }
}
