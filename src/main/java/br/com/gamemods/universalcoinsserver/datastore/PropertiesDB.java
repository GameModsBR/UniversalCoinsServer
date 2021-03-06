package br.com.gamemods.universalcoinsserver.datastore;

import br.com.gamemods.universalcoinsserver.UniversalCoinsServer;
import br.com.gamemods.universalcoinsserver.api.UniversalCoinsServerAPI;
import br.com.gamemods.universalcoinsserver.blocks.PlayerOwned;
import br.com.gamemods.universalcoinsserver.tile.TileVendor;
import cpw.mods.fml.common.registry.GameData;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

public class PropertiesDB implements CardDataBase
{
    private final File baseDir, accounts, players, logs;
    private SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z: ");
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy.MM.dd-HH");

    public PropertiesDB(File baseDir) throws IOException
    {
        this.baseDir = baseDir;
        if(!baseDir.isDirectory() && !baseDir.mkdirs())
            throw new IOException(baseDir.getAbsolutePath()+" is not a directory");

        accounts = createDir(baseDir, "accounts");
        players = createDir(baseDir, "players");
        logs = createDir(baseDir, "logs");
    }

    private File createDir(File base, String name) throws IOException
    {
        File dir = new File(base, name);
        if(!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Failed to create dir: "+dir.getAbsolutePath());
        return dir;
    }

    private Properties loadProperties(File file) throws DataStoreException
    {
        if(!file.isFile())
            return null;

        Properties properties = new SortedProperties();
        try(FileReader reader = new FileReader(file))
        {
            properties.load(reader);
        }
        catch (Exception e)
        {
            throw new DataStoreException(e);
        }

        return properties;
    }

    @Nonnull
    private Properties loadPlayer(UUID playerId) throws DataStoreException
    {
        if(playerId == null)
            throw new NullPointerException("playerId");

        File playerFile = new File(players, playerId+".properties");
        Properties playerData;
        if(!playerFile.isFile() || (playerData = loadProperties(playerFile)) == null)
            playerData = new SortedProperties();

        if(!playerData.containsKey("version"))
            playerData.setProperty("version", Integer.toString(Integer.MIN_VALUE));
        if(!playerData.containsKey("id"))
            playerData.setProperty("id", playerId.toString());

        return playerData;
    }

    private Properties loadAccount(String account) throws DataStoreException
    {
        return loadProperties(new File(accounts, account+".properties"));
    }

    private void saveAccount(String account, Properties properties) throws DataStoreException
    {
        File accountFile = new File(accounts, account+".properties");
        try(FileWriter writer = new FileWriter(accountFile))
        {
            properties.store(writer, "Account: "+account);
        }
        catch (Exception e)
        {
            throw new DataStoreException(e);
        }
    }

    @Nonnull
    @Override
    public PlayerData getPlayerData(@Nonnull UUID playerUID) throws DataStoreException
    {
        Properties properties = loadPlayer(playerUID);
        try
        {
            int version = Integer.parseInt(properties.getProperty("version", Integer.toString(Integer.MIN_VALUE)));
            String number = properties.getProperty("account");
            AccountAddress primary;
            List<AccountAddress> alternativeAccounts;
            AccountAddress removedPrimary=null;
            List<AccountAddress> removedAlternatives=null;
            if(number == null)
                primary = null;
            else
            {
                String[] split = number.split(";", 2);
                Properties accountProperties = loadAccount(split[0]);
                AccountAddress address = new AccountAddress(split[0], split[1], playerUID);
                if(accountProperties == null || accountProperties.getProperty("removed", "false").equals("true"))
                {
                    removedPrimary = address;
                    primary = null;
                }
                else
                    primary = address;
            }

            number = properties.getProperty("alternative.accounts");
            if(number == null)
                alternativeAccounts = null;
            else
            {
                String[] accountSplit = number.split("\\|");
                alternativeAccounts = new ArrayList<>(accountSplit.length);
                for(String str: accountSplit)
                {
                    String[] split = str.split(";",2);
                    Properties accountProperties = loadAccount(split[0]);
                    AccountAddress address = new AccountAddress(split[0], split[1], playerUID);
                    if(accountProperties == null || accountProperties.getProperty("removed", "false").equals("true"))
                    {
                        if(removedAlternatives == null)
                            removedAlternatives = new ArrayList<>(1);
                        removedAlternatives.add(address);
                        continue;
                    }

                    alternativeAccounts.add(address);
                }
            }

            if(removedPrimary != null)
            {
                concat(properties, "removed.primary", removedPrimary);
                properties.setProperty("account","");
            }

            if(removedAlternatives != null)
            {
                for (AccountAddress address : removedAlternatives)
                    concat(properties, "removed.alternative", address);

                properties.setProperty("alternative.accounts", "");
                for(AccountAddress address: alternativeAccounts)
                    concat(properties, "alternative.accounts", address);
            }

            if(removedPrimary != null || removedAlternatives != null)
            {
                incrementInt(properties, "version", Integer.MIN_VALUE);
                try(FileWriter writer = new FileWriter(getPlayerFile(playerUID)))
                {
                    properties.store(writer, "Removed some accounts");
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }


            return new PlayerData(version, playerUID, primary, alternativeAccounts);
        }
        catch (Exception e)
        {
            throw new DataStoreException(e);
        }
    }

    private void concat(Properties properties, String key, AccountAddress address)
    {
        String val = properties.getProperty("key", "");
        if(val.isEmpty())
            val = address.getNumber()+";"+address.getName();
        else
            val += "|"+address.getNumber()+";"+address.getName();
        properties.setProperty(key, val);
    }

    private String generateAccountNumber()
    {
        String str = Long.toString((long) (Math.floor(Math.random() * 99999999999L) + 11111111111L));
        return str.substring(0,3)+"."+str.substring(3,6)+"."+str.substring(6,9)+"-"+str.substring(9,11);
    }

    @Nonnull
    @Override
    public AccountAddress createPrimaryAccount(@Nonnull UUID playerUID, @Nonnull String name) throws DataStoreException, DuplicatedKeyException
    {
        Properties playerData = loadPlayer(playerUID);
        if(playerData.containsKey("account"))
            throw new DuplicatedKeyException("Player "+playerUID+" already have an account: "+playerData.getProperty("account"));

        try
        {
            int version = Integer.parseInt(playerData.getProperty("version", Integer.toString(Integer.MIN_VALUE)));
            AccountAddress account = createAccount(playerUID, name);

            playerData.setProperty("version", Integer.toString(version + 1));
            playerData.setProperty("account", account.getNumber() + ";" + account.getName());
            try (FileWriter writer = new FileWriter(new File(players, playerUID + ".properties")))
            {
                playerData.store(writer, "Primary account created");
            }

            return account;
        }
        catch (DataStoreException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new DataStoreException(e);
        }
    }

    private AccountAddress createAccount(UUID playerUID, String name) throws DataStoreException
    {
        try
        {
            String number;
            File file;
            do
            {
                number = generateAccountNumber();
                file = new File(accounts, number+".properties");
            } while (file.exists());

            Properties properties = new SortedProperties();
            properties.setProperty("version", Integer.toString(Integer.MIN_VALUE));
            properties.setProperty("number", number);
            properties.setProperty("owner.id", playerUID.toString());
            properties.setProperty("balance", "0");
            properties.setProperty("name", name);

            try(FileWriter writer = new FileWriter(file))
            {
                properties.store(writer, "Recently created");
            }

            return new AccountAddress(number, name, playerUID);
        }
        catch (Exception e)
        {
            throw new DataStoreException(e);
        }
    }

    @Override
    public UUID getAccountOwner(@Nonnull Object account) throws DataStoreException
    {
        Properties properties = loadAccount(account.toString());
        if (properties == null || properties.getProperty("removed", "false").equals("true"))
            return null;
        try
        {
            return UUID.fromString(properties.getProperty("owner.id"));
        }
        catch (Exception e)
        {
            throw new DataStoreException(e);
        }
    }

    @Override
    public int getAccountBalance(@Nonnull Object account) throws DataStoreException
    {
        if(account instanceof AccountAddress) account = ((AccountAddress) account).getNumber();
        Properties properties = loadAccount(account.toString());
        if(properties == null)
            return -1;

        try
        {
            return Integer.parseInt(properties.getProperty("balance", "0"));
        }
        catch (Exception e)
        {
            throw new DataStoreException(e);
        }
    }

    private int canDeposit(long balance, int deposit)
    {
        balance += deposit;
        balance = Integer.MAX_VALUE - balance;
        if(balance < Integer.MIN_VALUE)
            return Integer.MIN_VALUE;
        else if(balance > Integer.MAX_VALUE)
            return Integer.MAX_VALUE;
        return (int) balance;
    }

    @Override
    public int canDeposit(@Nonnull Object account, int coins) throws DataStoreException
    {
        if(account instanceof AccountAddress) account = ((AccountAddress) account).getNumber();
        Properties properties = loadAccount(account.toString());

        try
        {
            return canDeposit(Integer.parseInt(properties.getProperty("balance")), coins);
        }
        catch (Exception e)
        {
            throw new DataStoreException(e);
        }
    }

    @Override
    public int canDeposit(@Nonnull Object account, ItemStack coins) throws DataStoreException
    {
        return canDeposit(account, UniversalCoinsServerAPI.stackValue(coins));
    }

    @Override
    public int canDeposit(@Nonnull Object account, Collection<ItemStack> coins) throws DataStoreException
    {
        return canDeposit(account, UniversalCoinsServerAPI.stackValue(coins));
    }

    @Override
    public int depositToAccount(@Nonnull Object account, ItemStack coins, @Nonnull Transaction transaction) throws DataStoreException
    {
        return depositToAccount(account, Collections.singleton(coins), transaction);
    }

    private int incrementInt(Properties properties, String key, int increment, int defaultValue)
    {
        int current = Integer.parseInt(properties.getProperty(key, Integer.toString(defaultValue)));
        current += increment;
        properties.setProperty(key, Integer.toString(current));
        return current;
    }

    private int incrementInt(Properties properties, String key, int defaultValue)
    {
        return incrementInt(properties, key, 1, defaultValue);
    }

    private File getAccountFile(String account)
    {
        return new File(accounts, account+".properties");
    }

    private File getCustomAccountFile(String customAccountName) throws IOException
    {
        return new File(createDir(accounts, "custom"), customAccountName.toLowerCase()+".properties");
    }

    private File getPlayerFile(UUID playerUID)
    {
        return new File(players, playerUID + ".properties");
    }

    @Override
    public AccountAddress getCustomAccountByName(@Nonnull String customAccountName) throws DataStoreException
    {
        try
        {
            File file = getCustomAccountFile(customAccountName);
            if(!file.exists())
                return null;

            Properties properties = new Properties();
            try(FileReader reader = new FileReader(file))
            {
                properties.load(reader);
            }

            if(properties.getProperty("removed", "false").equals("true"))
                return null;

            return new AccountAddress(properties.getProperty("number"), properties.getProperty("name"),
                    UUID.fromString(properties.getProperty("owner")));
        }
        catch (Exception e)
        {
            throw new DataStoreException(e);
        }
    }

    private int readInt(Properties properties, String key, int defaultValue)
    {
        return Integer.parseInt(properties.getProperty(key, Integer.toString(defaultValue)));
    }

    private int readVersion(Properties properties)
    {
        return readInt(properties, "version", Integer.MIN_VALUE);
    }

    @Nonnull
    @Override
    public AccountAddress transferAccount(@Nonnull AccountAddress origin, @Nonnull String destiny, Machine machine, Operator operator)
            throws DataStoreException, AccountNotFoundException, DuplicatedKeyException
    {
        AccountAddress customAccountByName = getCustomAccountByName(origin.getName());
        if(customAccountByName == null) throw new AccountNotFoundException(origin.getNumber());
        return transferAccount(customAccountByName, destiny, machine, operator, false);
    }

    @Nonnull
    @Override
    public AccountAddress transferPrimaryAccount(@Nonnull AccountAddress primaryAccount, @Nonnull String newName, Machine machine, Operator operator)
            throws DataStoreException, AccountNotFoundException
    {
        try
        {
            return transferAccount(primaryAccount, newName, machine, operator, true);
        }
        catch (DuplicatedKeyException e)
        {
            throw new DataStoreException(e);
        }
    }

    private AccountAddress transferAccount(AccountAddress origin, String destiny, Machine machine, Operator operator, boolean primary)
            throws DataStoreException, AccountNotFoundException, DuplicatedKeyException
    {
        Properties originAccount = loadAccount(origin.getNumber().toString());
        if(originAccount == null || originAccount.getProperty("removed","false").equals("true"))
            throw new AccountNotFoundException(origin.getNumber());

        Properties playerData = loadPlayer(origin.getOwner());
        int playerVersion = readVersion(playerData);
        int originVersion = readVersion(originAccount);

        AccountAddress address = (primary)? createAccount(origin.getOwner(), destiny) : createCustomAccount(origin.getOwner(), destiny);

        Properties destinyAccount = loadAccount(address.getNumber().toString());
        destinyAccount.setProperty("balance", originAccount.getProperty("balance", "0"));
        originAccount.setProperty("balance", "0");
        originAccount.setProperty("removed", "true");
        originAccount.setProperty("transferred.number", address.getNumber().toString());
        originAccount.setProperty("transferred.name", address.getName());
        if(primary)
            playerData.setProperty("account", address.getNumber()+";"+address.getName());
        else
        {
            String property = playerData.getProperty("alternative.accounts", "").replaceFirst("\\|?" + Pattern.quote(origin.getNumber() + ";" + origin.getName()), "").replaceFirst("^\\|", "");
            if (property.isEmpty())
                playerData.setProperty("alternative.accounts", address.getNumber() + ";" + address.getName());
            else
                playerData.setProperty("alternative.accounts", property + "|" + address.getNumber() + ";" + address.getName());
        }

        incrementInt(playerData, "version", 2, Integer.MIN_VALUE);
        incrementInt(originAccount, "version", Integer.MIN_VALUE);
        incrementInt(destinyAccount, "version", Integer.MIN_VALUE);

        try
        {
            ItemStack oldCard = UniversalCoinsServerAPI.createCard(origin, !primary);
            ItemStack newCard = UniversalCoinsServerAPI.createCard(address, !primary);
            int balance = readInt(destinyAccount, "balance", 0);
            Transaction transaction = new Transaction(machine, Transaction.Operation.TRANSFER_ACCOUNT, operator,
                    new Transaction.CardCoinSource(oldCard, origin, balance, 0),
                    new Transaction.CardCoinSource(newCard, address, 0, balance),
                    null);

            saveTransaction(transaction);


            try (FileWriter writer = new FileWriter(getAccountFile(origin.getNumber().toString())))
            {
                originAccount.store(writer, "Transferred to " + address.getNumber());
            }

            try(FileWriter writer = new FileWriter(getAccountFile(address.getNumber().toString())))
            {
                destinyAccount.store(writer, "Transferred from "+origin.getNumber());
            }

            try(FileWriter writer = new FileWriter(getPlayerFile(origin.getOwner())))
            {
                playerData.store(writer, "Transferred "+origin.getNumber()+"("+origin.getName()+") to "+address.getNumber()+"("+address.getName()+")");
            }

            if(!primary) try
            {
                Properties properties = new SortedProperties();
                File customAccount = getCustomAccountFile(origin.getName());
                try(FileReader reader = new FileReader(customAccount))
                {
                    properties.load(reader);
                }

                properties.setProperty("removed", "true");
                properties.setProperty("transferred.number", address.getNumber().toString());
                properties.setProperty("transferred.name", address.getName());
                incrementInt(properties, "version", Integer.MIN_VALUE);

                try(FileWriter writer = new FileWriter(customAccount))
                {
                    properties.store(writer, "Transferred to " + address.getNumber());
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            return address;
        }
        catch (Exception e)
        {
            throw new DataStoreException(e);
        }
    }

    @Nonnull
    @Override
    public AccountAddress createCustomAccount(@Nonnull UUID playerUID, @Nonnull String customAccountName)
            throws DataStoreException, DuplicatedKeyException
    {
        Properties playerProperties = loadPlayer(playerUID);
        try
        {
            int version = readVersion(playerProperties);

            File file = getCustomAccountFile(customAccountName);
            if(file.exists() && getCustomAccountByName(customAccountName) != null)
                throw new DataBaseException("Account " + customAccountName + " already exists");

            AccountAddress account = createAccount(playerUID, customAccountName);
            String property = playerProperties.getProperty("alternative.accounts", "");
            String append = account.getNumber()+";"+account.getName();
            if(property.isEmpty())
                property = append;
            else
                property += "|"+append;
            version++;
            playerProperties.setProperty("alternative.accounts", property);
            playerProperties.setProperty("version", Integer.toString(version));

            Properties custom = new SortedProperties();
            custom.setProperty("number", account.getNumber().toString());
            custom.setProperty("name", account.getName());
            custom.setProperty("owner",account.getOwner().toString());
            custom.setProperty("version",Integer.toString(Integer.MIN_VALUE));
            try(FileWriter writer = new FileWriter(file))
            {
                custom.store(writer, "Account created");
            }

            try(FileWriter writer = new FileWriter(getPlayerFile(playerUID)))
            {
                playerProperties.store(writer, "Custom account '"+customAccountName+"' created");
            }

            return account;
        }
        catch (DataStoreException|DuplicatedKeyException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new DataStoreException(e);
        }
    }

    @Override
    public void processTrade(@Nonnull Transaction transaction) throws DataStoreException, OutOfCoinsException
    {
        Transaction.CoinSource ownerCoinSource = transaction.getOwnerCoinSource();
        Transaction.CoinSource userCoinSource = transaction.getUserCoinSource();

        if(ownerCoinSource instanceof Transaction.CardCoinSource)
        {
            int difference = ownerCoinSource.getBalanceAfter() - ownerCoinSource.getBalanceBefore();
            if(difference < 0)
                takeCoins(((Transaction.CardCoinSource) ownerCoinSource).getAccountAddress(), -difference);
            else
                deposit(((Transaction.CardCoinSource) ownerCoinSource).getAccountAddress(), difference);
        }

        if(userCoinSource instanceof Transaction.CardCoinSource)
        {
            int difference = userCoinSource.getBalanceAfter() - userCoinSource.getBalanceBefore();
            if(difference < 0)
                takeCoins(((Transaction.CardCoinSource) userCoinSource).getAccountAddress(), -difference);
            else
                deposit(((Transaction.CardCoinSource) userCoinSource).getAccountAddress(), difference);
        }

        saveTransaction(transaction);
    }


    @Override
    public int takeFromAccount(@Nonnull Object account, int amount, @Nonnull Transaction transaction) throws DataStoreException, OutOfCoinsException
    {
        Object[] ret = takeCoins(account, amount);
        if(Boolean.TRUE.equals(ret[0]))
        {
            try
            {
                saveTransaction(transaction);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }

        return (int) ret[1];
    }

    private Object[] takeCoins(Object account, int amount)
            throws DataStoreException, OutOfCoinsException
    {
        if(account instanceof AccountAddress) account = ((AccountAddress) account).getNumber();
        Properties properties = loadAccount(account.toString());
        try
        {
            if(amount == 0)
                return new Object[]{false,Integer.parseInt(properties.getProperty("balance", "0"))};
            else if(amount < 0)
                throw new IllegalArgumentException("amount < 0: "+amount);

            int newBalance = incrementInt(properties, "balance", -amount, 0);
            if(newBalance < 0)
                throw new OutOfCoinsException(-newBalance);
            incrementInt(properties, "version", Integer.MIN_VALUE);

            try(FileWriter writer = new FileWriter(getAccountFile(account.toString())))
            {
                properties.store(writer, "Took "+amount+" from balance");;
            }

            return new Object[]{true,newBalance};
        }
        catch (OutOfCoinsException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new DataStoreException(e);
        }
    }

    @Override
    public int depositToAccount(@Nonnull Object account, Collection<ItemStack> coinsStacks, @Nonnull Transaction transaction) throws DataStoreException
    {
        if(account instanceof AccountAddress) account = ((AccountAddress) account).getNumber();
        int value = UniversalCoinsServerAPI.stackValue(coinsStacks);
        if(value == 0)
            return 0;

        Properties properties = loadAccount(account.toString());
        return deposit(properties, account.toString(), value, transaction);
    }

    private int deposit(Object account, int value) throws DataStoreException
    {
        if(account instanceof AccountAddress) account = ((AccountAddress) account).getNumber();
        Properties properties = loadAccount(account.toString());
        return (int) deposit(properties, account.toString(), value)[1];
    }

    private int deposit(Properties properties, String account, int value, Transaction transaction) throws DataStoreException
    {
        Object[] ret = deposit(properties, account, value);
        if(Boolean.TRUE.equals(ret[0]))
            try
            {
                saveTransaction(transaction);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

        return (int) ret[1];
    }

    private Object[] deposit(Properties properties, String account, int value) throws DataStoreException
    {
        if(value == 0)
            return new Object[]{false, 0};
        else if(value < 0)
            throw new IllegalArgumentException("value < 0: "+value);

        try
        {
            int balance = Integer.parseInt(properties.getProperty("balance"));
            if(canDeposit(balance, value) < 0)
                return new Object[]{false, 0};

            properties.setProperty("balance", Integer.toString(balance+value));
            incrementInt(properties, "version", Integer.MIN_VALUE);

            try(FileWriter writer = new FileWriter(getAccountFile(account)))
            {
                properties.store(writer, "Balance increased by "+value);
            }

            return new Object[]{true, 0};
        }
        catch (Exception e)
        {
            throw new DataStoreException(e);
        }
    }

    @Override
    public int depositToAccount(@Nonnull Object account, int value, @Nonnull Transaction transaction) throws DataStoreException
    {
        if(account instanceof AccountAddress) account = ((AccountAddress) account).getNumber();
        Properties properties = loadAccount(account.toString());
        return deposit(properties, account.toString(), value, transaction);
    }

    private File getMachineLogFile(Machine machine) throws IOException
    {
        File machineDir = createDir(logs, "machine");
        return new File(machineDir, machine.getMachineId()+".log");
    }

    private void addData(StringBuilder sb, Machine machine)
    {
        if(machine instanceof PlayerOwned)
        {
            sb.append(" | PlayerOwner: ").append(((PlayerOwned) machine).getOwnerId());
        }

        if(machine instanceof TileEntity)
        {
            TileEntity te = (TileEntity)machine;
            World worldObj = te.getWorldObj();
            sb.append(" | DIM:").append(worldObj==null?"?":worldObj.provider.dimensionId)
                    .append(" | X:").append(te.xCoord).append(" | Y:").append(te.yCoord).append(" | Z:").append(te.zCoord)
                    .append(" | Block:").append(worldObj==null?"?":GameData.getBlockRegistry().getNameForObject(te.getBlockType()))
                    .append(" | BlockMeta:").append(worldObj==null?"?":te.getBlockMetadata());
        }
    }

    private File getMachineFile(Machine machine) throws IOException
    {
        File file = createDir(baseDir, "machines");
        return new File(file, machine.getMachineId()+".properties");
    }

    private Properties loadMachineProperties(Machine machine) throws DataBaseException
    {
        try
        {
            return loadProperties(getMachineFile(machine));
        }
        catch (Exception e)
        {
            throw new DataBaseException(e);
        }
    }

    private void incrementTransactions(Machine machine, int increment, UUID lastTransaction) throws DataBaseException
    {
        try
        {
            File file = getMachineFile(machine);
            Properties properties = loadMachineProperties(machine);
            if(properties == null)
            {
                saveMachine(machine);
                properties = loadMachineProperties(machine);
                if(properties == null)
                    throw new DataBaseException("Failed to load machine properties: "+machine.getMachineId());
            }

            storeMachine(properties, machine);

            int transactions = Integer.parseInt(properties.getProperty("transactions", "0"));
            properties.setProperty("transactions", Integer.toString(transactions + increment));
            properties.setProperty("transaction.last", lastTransaction.toString());

            try(FileWriter writer = new FileWriter(file))
            {
                normalize(properties);
                properties.store(writer, "Last transaction: "+lastTransaction);
            }
        }
        catch (Exception e)
        {
            throw new DataBaseException(e);
        }
    }

    private  void storeMachine(Properties properties, Machine machine)
    {
        store(properties, "machine", machine);

        TileEntity te = machine.getMachineEntity();
        if(te instanceof TileVendor)
        {
            TileVendor vendor = (TileVendor) te;
            properties.put("vendor.owner.name", String.valueOf(vendor.ownerName));
            properties.put("vendor.coins.owner", vendor.ownerCoins);
            properties.put("vendor.coins.user", vendor.userCoins);
            properties.put("vendor.price", vendor.price);
            properties.put("vendor.infinite", vendor.infinite);
            properties.put("vendor.sell", vendor.sellToUser);
        }
    }

    public void saveMachine(Machine machine) throws DataStoreException
    {
        try
        {
            File file = getMachineFile(machine);
            Properties properties = loadMachineProperties(machine);
            if(properties == null)
            {
                properties = new SortedProperties();
                properties.put("creation", System.currentTimeMillis());
                properties.put("transactions", "0");
            }

            storeMachine(properties, machine);

            try(FileWriter writer = new FileWriter(file))
            {
                normalize(properties);
                properties.store(writer, "");
            }
        }
        catch (Exception e)
        {
            throw new DataStoreException(e);
        }
    }

    @Override
    public void saveNewMachine(@Nonnull Machine machine) throws DataStoreException
    {
        File file;
        try
        {
            file = getMachineLogFile(machine);
        }
        catch (IOException e)
        {
            throw new DataStoreException(e);
        }

        try(FileWriter writer = new FileWriter(file, true))
        {
            StringBuilder sb = new StringBuilder(dateTimeFormat.format(new Date()))
                    .append("Machine created | MachineID:").append(machine.getMachineId());

            addData(sb, machine);

            sb.append("\n");
            writer.write(sb.toString());
        }
        catch (Exception e)
        {
            throw new DataStoreException(e);
        }

        saveMachine(machine);
    }

    @Override
    public void saveTransaction(@Nonnull Transaction transaction) throws DataStoreException
    {
        Machine machine = transaction.getMachine();
        if (machine == null)
            return;

        try
        {
            File file = getMachineLogFile(machine);

            try (FileWriter writer = new FileWriter(file, true))
            {
                StringBuilder sb = new StringBuilder(dateTimeFormat.format(new Date()))
                        .append("Transaction processed")
                        .append(" | TransactionID:").append(transaction.getId());

                addData(sb, machine);

                sb.append(" | TransactionData: ")
                        .append(transaction)
                        .append("\n");


                writer.write(sb.toString());
            }

            File dir = createDir(logs, "transactions");
            Date date = new Date(transaction.getTime());
            dir = createDir(dir, dateFormat.format(date));

            file =  new File(dir, transaction.getId()+".properties");
            Properties properties = new SortedProperties();
            properties.put("id", transaction.getId());
            properties.put("time", transaction.getTime());
            properties.put("operation", transaction.getOperation());
            properties.put("infinite", transaction.isInfiniteMachine());
            properties.put("quantity", transaction.getQuantity());
            properties.put("price", transaction.getPrice());
            properties.put("price.total", transaction.getTotalPrice());
            store(properties, "coins.user", transaction.getUserCoinSource());
            store(properties, "coins.owner", transaction.getOwnerCoinSource());
            store(properties, "operator", transaction.getOperator());
            store(properties, "machine", transaction.getMachine());
            store(properties, "product", transaction.getProduct());
            store(properties, "trade", transaction.getTrade());

            try(FileWriter writer = new FileWriter(file))
            {
                normalize(properties);
                properties.store(writer, "Transaction on "+dateTimeFormat.format(date));
            }

            incrementTransactions(machine, 1, transaction.getId());
        }
        catch (Exception e)
        {
            throw new DataStoreException(e);
        }
    }

    private void normalize(Properties properties)
    {
        HashSet<Object> keys = new HashSet<>(properties.keySet());
        for(Object key: keys)
        {
            Object value = properties.get(key);
            if(!(value instanceof String))
                properties.put(key, String.valueOf(value));
        }
    }

    private void store(Properties properties, String key, Transaction.CoinSource coinSource)
    {
        if(coinSource != null)
        {
            properties.put(key+".balance.before", coinSource.getBalanceBefore());
            properties.put(key+".balance.after", coinSource.getBalanceAfter());
            if(coinSource instanceof Transaction.MachineCoinSource)
            {
                properties.put(key+".type", "machine");
                properties.put(key+".machine.id", ((Transaction.MachineCoinSource) coinSource).getMachine().getMachineId());
            }
            else if(coinSource instanceof Transaction.CardCoinSource)
            {
                properties.put(key+".type", "card");
                Transaction.CardCoinSource card = (Transaction.CardCoinSource) coinSource;
                AccountAddress accountAddress = card.getAccountAddress();
                properties.put(key+".account.number", accountAddress.getNumber());
                properties.put(key+".account.owner", accountAddress.getOwner());
                properties.put(key+".account.name", accountAddress.getName());
                store(properties, key+".card", card.getCard());
            }
            else if(coinSource instanceof Transaction.InventoryCoinSource)
            {
                properties.put(key+".type", "inventory");
                store(properties, key+".holder", ((Transaction.InventoryCoinSource) coinSource).getOperator());
            }
        }
    }

    private void store(Properties properties, String key, Operator operator)
    {
        if(operator != null)
        {
            if(operator instanceof PlayerOperator)
            {
                properties.put(key+".type", "player");
                properties.put(key+".player", ((PlayerOperator) operator).getPlayerId());
            }
            else if(operator instanceof BlockOperator)
            {
                if(operator instanceof MachineOperator)
                {
                    properties.put(key+".type", "machine");
                    properties.put(key+".machine.id", ((MachineOperator) operator).getMachine().getMachineId());
                }
                else
                {
                    properties.put(key+".type", "block");
                }
                BlockOperator bo = (BlockOperator) operator;
                if(bo.getOwner() != null)
                    properties.put(key+".owner", bo.getOwner());

                properties.put(key+".block.x", bo.getX());
                properties.put(key+".block.y", bo.getY());
                properties.put(key+".block.z", bo.getZ());
                properties.put(key+".block.dim", bo.getDim());
                properties.put(key+".block.meta", bo.getBlockMeta());
                properties.put(key+".block.id", bo.getBlockId());
            }
        }
    }

    private void store(Properties properties, String key, Machine machine)
    {
        if(machine != null)
        {
            properties.put(key+".id", machine.getMachineId());
            if(machine instanceof PlayerOwned)
            {
                UUID ownerId = ((PlayerOwned) machine).getOwnerId();
                properties.put(key+".owner", String.valueOf(ownerId));
            }

            TileEntity te = machine.getMachineEntity();
            if(te != null)
            {
                World worldObj = te.getWorldObj();
                properties.put(key+".tile.dim", worldObj==null?"null":worldObj.provider.dimensionId);
                properties.put(key+".tile.x", te.xCoord);
                properties.put(key+".tile.y", te.yCoord);
                properties.put(key+".tile.z", te.zCoord);
                try
                {
                    properties.put(key + ".tile.block", String.valueOf(GameData.getBlockRegistry().getNameForObject(te.getBlockType())));
                    properties.put(key + ".tile.block.meta", te.getBlockMetadata());
                }
                catch (Exception ignored)
                { }
            }
        }
    }

    private void store(Properties properties, String key, ItemStack stack)
    {
        if(stack != null)
        {
            properties.put(key, String.valueOf(stack));
            properties.put(key+".type", String.valueOf(GameData.getItemRegistry().getNameForObject(stack.getItem())));
            properties.put(key+".meta", stack.getItemDamage());
            properties.put(key+".amount", stack.stackSize);
            if(stack.hasTagCompound())
                properties.put(key+".tags", stack.stackTagCompound.toString());
        }
    }

    @Override
    public Collection<PlayerData> getAllPlayerData() throws DataStoreException
    {
        File[] playerFiles = players.listFiles();
        if(playerFiles == null)
            throw new DataStoreException("Failed to list files on "+players.getAbsolutePath());

        ArrayList<PlayerData> list = new ArrayList<>(playerFiles.length);
        for(File playerFile: playerFiles)
        {
            String fileName = playerFile.getName();
            if(playerFile.isFile() && fileName.toLowerCase().endsWith(".properties"))
                list.add(getPlayerData(UUID.fromString(fileName.substring(0, fileName.length()-".properties".length()))));
        }
        return list;
    }

    @Override
    public Map<AccountAddress, Integer> getAllAccountsBalance() throws DataStoreException
    {
        File[] accountFiles = accounts.listFiles();
        if(accountFiles == null)
            throw new DataStoreException("Failed to list files on "+accounts.getAbsolutePath());

        Map<AccountAddress, Integer> map = new HashMap<>(accountFiles.length);
        for(File accountFile: accountFiles)
        {
            String fileName = accountFile.getName();
            if(accountFile.isFile() && fileName.toLowerCase().endsWith(".properties"))
            {
                String account = fileName.substring(0, fileName.length() - ".properties".length());
                Properties properties = loadAccount(account);
                if(properties.getProperty("removed", "false").equals("true"))
                    continue;

                int balance = Integer.parseInt(properties.getProperty("balance", "0"));
                UUID owner = UUID.fromString(properties.getProperty("owner.id"));
                String name = properties.getProperty("name", account);
                map.put(new AccountAddress(account, name, owner), balance);
            }
        }
        return map;
    }

    @Override
    public AccountAddress renamePrimaryAccount(AccountAddress primaryAccount, String playerName) throws DataStoreException, AccountNotFoundException
    {
        Properties properties = loadAccount(primaryAccount.getNumber().toString());
        if(properties == null) throw new AccountNotFoundException(primaryAccount);

        incrementInt(properties, "version", 1, Integer.MIN_VALUE);
        properties.setProperty("name", playerName);
        saveAccount(primaryAccount.getNumber().toString(), properties);

        return new AccountAddress(primaryAccount.getNumber(), playerName, primaryAccount.getOwner());
    }

    @Override
    public void importData(CardDataBase original) throws DataStoreException
    {
        throw new DataStoreException(new UnsupportedOperationException());
    }

    static class SortedProperties extends Properties
    {
        @SuppressWarnings("unchecked")
        public Enumeration keys()
        {
            Enumeration<Object> keysEnum = super.keys();
            Vector<String> keyList = new Vector<>();
            while(keysEnum.hasMoreElements())
            {
                Object object = keysEnum.nextElement();
                keyList.add(String.valueOf(object));
            }
            Collections.sort(keyList);
            return keyList.elements();
        }
    }

    @Override
    public void updatePlayerName(@Nonnull UUID persistentID, @Nonnull String commandSenderName) throws DataStoreException
    {
        File playerFile = getPlayerFile(persistentID);
        Properties properties = loadPlayer(persistentID);
        incrementInt(properties, "version", Integer.MIN_VALUE);
        String previous = properties.getProperty("name");
        properties.setProperty("name", commandSenderName);
        try(FileWriter writer=new FileWriter(playerFile))
        {
            properties.store(writer, "Updated player name");
        }
        catch (IOException e)
        {
            throw new DataStoreException(e);
        }

        File namesDir = new File(players, "names");

        if(!namesDir.isDirectory() && !namesDir.mkdirs())
            throw new DataStoreException("Failed to create dir "+namesDir.getAbsolutePath());

        if(previous != null && !previous.isEmpty())
        {
            previous = previous.toLowerCase();
            File dir = new File(namesDir, previous.substring(0, 2));
            if(!dir.mkdirs())
                throw new DataStoreException("Failed to create dir "+dir.getAbsolutePath());

            File file = new File(dir, previous + ".properties");
            if(!file.delete())
                UniversalCoinsServer.logger.warn("Failed to delete file "+file);
        }

        String lowerCased = commandSenderName.toLowerCase();
        File dir = new File(namesDir, lowerCased.substring(0, 2));
        if(!dir.mkdirs())
            throw new DataStoreException("Failed to create dir "+dir.getAbsolutePath());


        File file = new File(dir, lowerCased+".properties");
        properties = new SortedProperties();
        properties.setProperty("player.id", persistentID.toString());
        properties.setProperty("player.name", commandSenderName);
        try(FileWriter writer = new FileWriter(file))
        {
            properties.store(writer, "Name Updated");
        }
        catch (IOException e)
        {
            throw new DataStoreException(e);
        }
    }

    @Nullable
    @Override
    public UUID getPlayerIdByName(@Nonnull String name) throws DataStoreException
    {
        if(name.length() < 2 || name.contains("."))
            return null;

        name = name.toLowerCase();
        File namesDir = new File(players, "names");
        File dir = new File(namesDir, name.substring(0, 2));
        if(!dir.isDirectory())
            return null;

        File[] namesFiles = dir.listFiles();
        if(namesFiles == null)
            return null;

        for(File file: namesFiles)
        {
            if(file.getName().equalsIgnoreCase(name+".properties"))
            {
                try(FileReader reader = new FileReader(file))
                {
                    Properties properties = new Properties();
                    properties.load(reader);
                    String property = properties.getProperty("player.id");
                    if(property == null)
                        return null;
                    return UUID.fromString(property);
                }
                catch (Exception e)
                {
                    throw new DataStoreException(e);
                }
            }
        }

        return null;
    }

    @Nullable
    @Override
    public Map<UUID, String> findPlayerByName(@Nonnull String name) throws DataStoreException
    {
        if(name.length() < 2 || name.contains("."))
            return null;

        name = name.toLowerCase();
        File namesDir = new File(players, "names");
        File dir = new File(namesDir, name.substring(0, 2));
        if(!dir.isDirectory())
            return null;

        File[] namesFiles = dir.listFiles();
        if(namesFiles == null)
            return null;

        Map<UUID, String> matches = new HashMap<>();
        for(File file: namesFiles)
        {
            if(file.getName().startsWith(name))
            {
                try(FileReader reader = new FileReader(file))
                {
                    Properties properties = new Properties();
                    properties.load(reader);
                    String property = properties.getProperty("player.id");
                    String playerName = properties.getProperty("player.name");
                    if(property == null || playerName == null)
                        return null;

                    matches.put(UUID.fromString(property), playerName);
                }
                catch (Exception e)
                {
                    throw new DataStoreException(e);
                }
            }
        }

        return matches;
    }

    @Override
    public boolean storePackage(@Nonnull ItemStack packageStack, ICommandSender sender, @Nonnull UUID targetId) throws DataStoreException
    {
        File dir = new File(players, "deliveries");
        dir = new File(dir, targetId.toString());
        if(!dir.mkdirs())
            throw new DataStoreException("Failed to create dir "+dir);

        try
        {
            File file = File.createTempFile("delivery_", ".properties", dir);
            SortedProperties properties = new SortedProperties();
            NBTTagCompound nbt = new NBTTagCompound();
            packageStack.writeToNBT(nbt);
            properties.setProperty("item", nbt.toString());
            properties.setProperty("sender.name", sender.getCommandSenderName());
            if(sender instanceof Entity)
                properties.setProperty("sender.id", ((Entity) sender).getPersistentID().toString());
            properties.setProperty("target.id", targetId.toString());
            try(FileWriter writer=new FileWriter(file))
            {
                properties.store(writer, "");
            }
            return true;
        } catch (IOException e)
        {
            throw new DataStoreException(e);
        }
    }

    @Override
    public void deliveryPackages(@Nonnull EntityPlayer player) throws DataStoreException
    {
        int firstEmptyStack = player.inventory.getFirstEmptyStack();
        if(firstEmptyStack == -1)
        {
            player.addChatComponentMessage(new ChatComponentTranslation("sign.warning.inventoryfull").setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED)));
            return;
        }

        File dir = new File(players, "deliveries"), deliveredDir = new File(dir, "delivered");
        dir = new File(dir, player.getPersistentID().toString());
        if(!dir.isDirectory())
            return;

        File[] files = dir.listFiles();
        if(files == null)
            return;

        try
        {
            for(File file: files)
            {
                String name = file.getName().toLowerCase();
                if(name.startsWith("delivery_") && name.endsWith(".properties"))
                {
                    if(firstEmptyStack == -1)
                    {
                        player.addChatComponentMessage(new ChatComponentTranslation("sign.warning.inventoryfull").setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED)));
                        return;
                    }

                    Properties properties = new Properties();
                    try(FileReader reader = new FileReader(file))
                    {
                        properties.load(reader);
                    }
                    NBTTagCompound nbt = (NBTTagCompound) JsonToNBT.func_150315_a(properties.getProperty("item"));
                    ItemStack stack = ItemStack.loadItemStackFromNBT(nbt);
                    String sender = properties.getProperty("sender.name");

                    long sent = Files.readAttributes(file.toPath(), BasicFileAttributes.class).creationTime().toMillis();

                    stack.stackTagCompound.setString("sender", sender);
                    stack.stackTagCompound.setLong("sent", sent);
                    long time = System.currentTimeMillis();
                    stack.stackTagCompound.setLong("received", time);

                    File destiny = new File(deliveredDir, file.getName());
                    Files.move(file.toPath(), destiny.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);

                    player.inventory.setInventorySlotContents(firstEmptyStack, stack);
                    player.addChatComponentMessage(
                            new ChatComponentText(sender)
                                    .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GREEN))
                                    .appendSibling(new ChatComponentTranslation("packager.message.sent"))
                    );

                    firstEmptyStack = player.inventory.getFirstEmptyStack();
                }
            }
        }
        catch (Exception e)
        {
            throw new DataStoreException(e);
        }
        finally
        {
            player.inventoryContainer.detectAndSendChanges();
        }
    }

    @Override
    public int getPendingDeliveries(@Nonnull UUID persistentID) throws DataStoreException
    {
        File dir = new File(players, "deliveries");
        dir = new File(dir, persistentID.toString());
        if(!dir.isDirectory())
            return 0;

        File[] files = dir.listFiles();
        if(files == null)
            return 0;

        int count = 0;
        for(File file: files)
        {
            String name = file.getName().toLowerCase();
            if(name.startsWith("delivery_") && name.endsWith(".properties"))
                count++;
        }

        return count;
    }
}
