package samples;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A clean example class. The reviewer should find little or nothing here.
 */
public final class Account {
    private final String id;
    private BigDecimal balance;

    /**
     * Creates an account with an opening balance.
     *
     * @param id      unique account id
     * @param opening opening balance, must not be negative
     */
    public Account(String id, BigDecimal opening) {
        if (opening.signum() < 0) {
            throw new IllegalArgumentException("Opening balance cannot be negative");
        }
        this.id = Objects.requireNonNull(id);
        this.balance = opening;
    }

    /**
     * Takes money out of the account.
     *
     * @param amount amount to withdraw
     * @return the new balance
     */
    public BigDecimal withdraw(BigDecimal amount) {
        if (amount.compareTo(balance) > 0) {
            throw new IllegalStateException("Insufficient funds");
        }
        balance = balance.subtract(amount);
        return balance;
    }

    /** @return the account id */
    public String getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Account && ((Account) o).id.equals(id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
