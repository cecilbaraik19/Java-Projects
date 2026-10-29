package com.cecil.qpg.store;

/**
 * Demo question bank, one question per line:
 *   unit | kind | difficulty | Bloom level | marks | question | options separated by ~ | answer
 * Kinds: MCQ, TF (true/false), FILL, SHORT, LONG, NUM (numerical).
 * Difficulty: E, M, H.  Bloom: R remember, U understand, AP apply, AN analyze, EV evaluate, C create.
 */
final class SeedQuestions {
    private SeedQuestions() {}

    // ================================================================== CSIT135 Operating Systems
    static final String OS = """
1|MCQ|E|R|1|Which of these is NOT an operating system?|Linux~Windows~Oracle~macOS|Oracle
1|MCQ|E|R|1|The core part of an operating system that always stays in memory is called the|Shell~Kernel~Compiler~Loader|Kernel
1|MCQ|E|U|1|A system call is used by a program to|Ask the OS for a service~Call another user~Restart the CPU~Format the disk|Ask the OS for a service
1|MCQ|M|U|1|In a time-sharing system the main goal is to|Reduce CPU idle time only~Give quick response to many users~Run one job at a time~Avoid using memory|Give quick response to many users
1|MCQ|M|U|1|Switching from user mode to kernel mode happens when|A program calls printf~A system call or interrupt occurs~The screen is refreshed~A variable is declared|A system call or interrupt occurs
1|MCQ|E|R|1|Which OS type is used in a car's airbag controller?|Batch OS~Real-time OS~Network OS~Time-sharing OS|Real-time OS
2|MCQ|E|R|1|A program in execution is called a|Thread~Process~File~Job queue|Process
2|MCQ|E|R|1|Which data structure stores all information about a process?|Page table~Process Control Block~File table~Inode|Process Control Block
2|MCQ|M|AP|1|Which scheduling algorithm can cause starvation of long processes?|FCFS~Round Robin~Shortest Job First~FIFO|Shortest Job First
2|MCQ|M|U|1|In Round Robin scheduling, a very large time quantum makes it behave like|SJF~Priority~FCFS~Multilevel queue|FCFS
2|MCQ|H|AN|1|Which of these is shared between threads of the same process?|Stack~Program counter~Registers~Heap memory|Heap memory
2|MCQ|M|U|1|The convoy effect is seen in which scheduling algorithm?|FCFS~Round Robin~SRTF~Priority with aging|FCFS
3|MCQ|E|R|1|A section of code where shared data is accessed is called the|Entry section~Critical section~Remainder section~Exit point|Critical section
3|MCQ|M|U|1|Which of these is NOT a necessary condition for deadlock?|Mutual exclusion~Hold and wait~Preemption~Circular wait|Preemption
3|MCQ|M|AP|1|A semaphore initialised to 1 is used as a|Counter of resources~Mutex lock~Timer~Page frame|Mutex lock
3|MCQ|H|AN|1|Banker's algorithm is used for deadlock|Prevention~Avoidance~Detection~Recovery|Avoidance
3|MCQ|M|U|1|Busy waiting wastes|Disk space~CPU cycles~Memory pages~Network bandwidth|CPU cycles
4|MCQ|E|R|1|Dividing physical memory into fixed-size blocks called frames is used in|Segmentation~Paging~Swapping only~Overlays|Paging
4|MCQ|M|U|1|External fragmentation is a problem in|Paging~Contiguous allocation~TLB~Demand paging only|Contiguous allocation
4|MCQ|M|AP|1|Belady's anomaly can happen with which page replacement algorithm?|LRU~Optimal~FIFO~LFU|FIFO
4|MCQ|H|AN|1|Thrashing happens when|The CPU is too fast~A process spends more time paging than executing~The disk is full~Too few processes run|A process spends more time paging than executing
4|MCQ|E|R|1|TLB stands for|Translation Lookaside Buffer~Table Lookup Block~Transfer Logic Buffer~Total Load Balance|Translation Lookaside Buffer
5|MCQ|E|R|1|Which disk scheduling algorithm serves the request closest to the head first?|FCFS~SSTF~SCAN~C-LOOK|SSTF
5|MCQ|M|U|1|In indexed file allocation, block addresses are kept in a|Linked list~Index block~FAT only~Boot block|Index block
5|MCQ|M|U|1|The elevator algorithm is another name for|SSTF~SCAN~FCFS~LOOK|SCAN
5|MCQ|H|AN|1|Which allocation method gives the fastest direct (random) access to a file?|Linked~Contiguous~Indexed with many levels~None of the above|Contiguous
1|TF|E|R|1|An operating system acts as an interface between the user and the computer hardware.||True
1|TF|M|U|1|A multiprogramming system can run only one program in memory at a time.||False
2|TF|E|U|1|Every process has its own Process Control Block.||True
2|TF|M|U|1|Round Robin scheduling is non-preemptive.||False
3|TF|M|U|1|Deadlock can occur even if only one process exists in the system.||False
3|TF|E|R|1|A binary semaphore can only take the values 0 and 1.||True
4|TF|M|U|1|Paging removes external fragmentation.||True
4|TF|H|AN|1|LRU page replacement suffers from Belady's anomaly.||False
5|TF|E|R|1|A directory is a special file that keeps information about other files.||True
5|TF|M|U|1|SSTF disk scheduling can cause starvation.||True
1|FILL|E|R|1|The program that loads the operating system when the computer starts is called the ________.||Bootstrap loader
2|FILL|E|R|1|The time from submission of a process to its completion is called ________ time.||Turnaround
2|FILL|M|U|1|Moving a process out of memory to disk and back again is called ________.||Swapping
3|FILL|M|R|1|The solution to the critical section problem for two processes using flag and turn is called ________'s solution.||Peterson
4|FILL|E|R|1|The address generated by the CPU is called the ________ address.||Logical
4|FILL|M|U|1|The hardware unit that maps logical addresses to physical addresses is the ________.||MMU (Memory Management Unit)
5|FILL|E|R|1|In Unix, the data structure that stores a file's details except its name is the ________.||Inode
5|FILL|M|U|1|The time for the disk arm to move to the required track is called ________ time.||Seek
1|SHORT|E|U|5|What is an operating system? Write any four of its functions.||OS = software that manages hardware and gives services to programs. Functions: process management, memory management, file management, device (I/O) management, security, user interface.
1|SHORT|M|U|5|Differentiate between a batch operating system and a time-sharing operating system.||Batch: jobs grouped and run one after another, no user interaction, long response. Time-sharing: CPU time divided into slices among many users, interactive, quick response.
1|SHORT|M|U|5|What is a system call? Explain any three types of system calls with examples.||Request from a program to the kernel. Types: process control (fork, exit), file management (open, read), device management (ioctl), information (getpid), communication (pipe).
1|SHORT|H|AN|5|Compare monolithic kernel and microkernel architecture. Which one is more reliable and why?||Monolithic: all services in kernel space, fast, one bug can crash all. Microkernel: minimal kernel, services in user space, message passing, slower but more reliable and secure.
2|SHORT|E|R|5|Draw and explain the process state diagram.||States: new, ready, running, waiting, terminated; transitions: admit, dispatch, interrupt, I/O wait, I/O complete, exit.
2|SHORT|M|U|5|What is a Process Control Block? List the information stored in it.||PCB stores process state, PID, program counter, CPU registers, scheduling info, memory info, accounting info, I/O status.
2|SHORT|M|AN|5|Differentiate between a process and a thread.||Process: own address space, heavy to create, separate resources. Thread: shares code, data and files of its process, own stack and registers, light, faster switching.
2|SHORT|H|EV|5|Compare preemptive and non-preemptive scheduling. Which is better for an interactive system? Justify.||Preemptive: CPU can be taken away (RR, SRTF). Non-preemptive: runs until it finishes or blocks (FCFS). Preemptive is better for interactive systems: good response time.
3|SHORT|E|U|5|What is the critical section problem? State the three requirements of a correct solution.||Problem: processes sharing data must not be in their critical section together. Requirements: mutual exclusion, progress, bounded waiting.
3|SHORT|M|U|5|What is a semaphore? Explain wait() and signal() operations.||Integer variable accessed by atomic wait (decrement, block if negative/zero) and signal (increment, wake a waiting process). Binary and counting semaphores.
3|SHORT|M|U|5|List and explain the four necessary conditions for deadlock.||Mutual exclusion, hold and wait, no preemption, circular wait; all four must hold together.
3|SHORT|H|AN|5|Explain the producer-consumer problem and how semaphores solve it.||Bounded buffer; semaphores mutex=1, empty=n, full=0; producer waits on empty and mutex, consumer waits on full and mutex; signals in reverse.
4|SHORT|E|U|5|Differentiate between logical address and physical address.||Logical: made by CPU, seen by program. Physical: actual RAM location. MMU translates logical to physical using base/relocation register or page table.
4|SHORT|M|U|5|What is paging? Explain with a neat diagram.||Logical memory split into pages, physical into frames of the same size; page table maps page number to frame number; address = page number + offset.
4|SHORT|M|AN|5|Differentiate between internal and external fragmentation.||Internal: unused space inside an allocated block (fixed partitions, paging last page). External: free memory scattered in small holes (variable partitions); fixed by compaction or paging.
5|SHORT|M|U|5|Explain any three file allocation methods.||Contiguous (fast, external fragmentation), linked (no fragmentation, slow random access), indexed (index block, direct access, overhead of index).
5|SHORT|E|R|5|What is a file? List common file attributes and operations.||Named collection of related data on storage. Attributes: name, type, size, location, protection, time. Operations: create, open, read, write, seek, delete, close.
5|SHORT|H|EV|5|Compare the single-level, two-level and tree-structured directory structures.||Single: all files in one directory, name clashes. Two-level: one directory per user. Tree: subdirectories, paths, grouping; most flexible and widely used.
1|SHORT|E|R|2|Define multiprogramming.||Keeping several programs in memory at once so the CPU always has something to run.
2|SHORT|E|R|2|What is context switching?||Saving the state of the running process and loading the state of the next one.
3|SHORT|M|U|2|What is a race condition?||When the result depends on the order in which processes access shared data.
4|SHORT|E|R|2|What is a page fault?||A trap raised when a program uses a page that is not in main memory.
5|SHORT|E|R|2|What is seek time?||Time taken by the disk arm to move to the track that holds the data.
1|LONG|M|U|10|Explain the different types of operating systems (batch, multiprogramming, time-sharing, distributed and real-time) with examples.||Each type: idea, working, advantages, disadvantages, example.
1|LONG|H|AN|10|Explain the structure of an operating system. Compare simple, layered, monolithic and microkernel structures with diagrams.||Diagrams of each, pros and cons, examples: MS-DOS, THE, Linux, Mach/QNX.
2|LONG|M|AP|10|Explain FCFS, SJF, Priority and Round Robin scheduling with one example each. Draw Gantt charts.||Algorithm, Gantt chart, waiting and turnaround time for each; compare.
2|LONG|H|AN|10|What are threads? Explain user-level and kernel-level threads and the multithreading models (many-to-one, one-to-one, many-to-many).||Thread definition, benefits, ULT vs KLT, three models with diagrams and pros and cons.
3|LONG|M|U|10|What is deadlock? Explain deadlock prevention and deadlock avoidance methods in detail.||Definition, four conditions, prevention by breaking each condition, avoidance with safe state and Banker's algorithm.
3|LONG|H|AN|10|Explain the Dining Philosophers problem. Give a semaphore-based solution and explain how it can still deadlock and how to avoid it.||Problem statement, chopstick semaphores, deadlock when all pick left, fixes: at most four sit, odd/even order, pick both at once.
3|LONG|H|EV|10|Explain deadlock detection and recovery. Compare it with deadlock prevention in terms of cost and resource use.||Wait-for graph, detection algorithm, recovery by killing or preempting; detection allows better use but costs checking time.
4|LONG|M|U|10|Explain virtual memory and demand paging. Describe the steps to handle a page fault with a diagram.||Virtual memory idea, lazy loading, valid-invalid bit, six steps of page fault handling, effective access time.
4|LONG|H|AN|10|Explain segmentation with a diagram. Compare paging and segmentation and explain segmentation with paging.||Segment table (base, limit), address translation, comparison table, combined scheme (Intel).
5|LONG|M|AP|10|Explain FCFS, SSTF, SCAN and C-SCAN disk scheduling with an example of your own and compare their head movement.||Request queue example, head movement for each, comparison.
2|NUM|M|AP|10|Processes P1, P2, P3, P4 arrive at time 0 with burst times 6, 8, 7 and 3 ms. Draw Gantt charts for FCFS and SJF and find the average waiting time for each.||FCFS: waiting 0, 6, 14, 21, average 10.25 ms. SJF order P4, P1, P3, P2: waiting P4=0, P1=3, P3=9, P2=16, average 7 ms.
2|NUM|H|AP|10|For processes P1 (burst 10), P2 (burst 4), P3 (burst 5) all arriving at time 0, apply Round Robin with time quantum 3 ms. Draw the Gantt chart and find the average turnaround time.||Gantt: P1 0-3, P2 3-6, P3 6-9, P1 9-12, P2 12-13, P3 13-15, P1 15-18, P1 18-19. Turnaround P1=19, P2=13, P3=15, average 15.67 ms.
3|NUM|H|AP|10|A system has 5 processes and 3 resource types A(10), B(5), C(7). Given the Allocation and Max tables in the standard textbook example, use Banker's algorithm to find whether the system is in a safe state and give the safe sequence.||Compute Need = Max - Allocation, Available = (3,3,2); safe sequence P1, P3, P4, P0, P2 (or any valid order); system is safe.
4|NUM|M|AP|10|For the reference string 7, 0, 1, 2, 0, 3, 0, 4, 2, 3, 0, 3, 2 with 3 frames, find the number of page faults using FIFO and LRU.||FIFO: 10 page faults. LRU: 9 page faults.
4|NUM|H|AN|10|For the reference string 1, 2, 3, 4, 1, 2, 5, 1, 2, 3, 4, 5, find the page faults with FIFO for 3 frames and 4 frames. What do you observe?||3 frames: 9 faults; 4 frames: 10 faults. More frames gave more faults: Belady's anomaly.
5|NUM|M|AP|10|A disk has 200 cylinders (0-199). The head is at 53 and the queue is 98, 183, 37, 122, 14, 124, 65, 67. Find the total head movement for FCFS and SSTF.||FCFS: 640 cylinders. SSTF: 236 cylinders.
""";

    // ================================================================== CSIT124 Java Programming
    static final String JAVA = """
1|MCQ|E|R|1|Java source code is compiled into|Machine code~Bytecode~Assembly~Object code for one CPU|Bytecode
1|MCQ|E|R|1|Which of these is NOT a primitive type in Java?|int~boolean~String~char|String
1|MCQ|M|U|1|The size of an int in Java is|16 bits~32 bits~64 bits~Depends on the machine|32 bits
1|MCQ|M|AP|1|What is the output of System.out.println(5 + 3 + "Java");|53Java~8Java~Java8~Error|8Java
1|MCQ|E|R|1|JVM stands for|Java Variable Machine~Java Virtual Machine~Joint Virtual Method~Java Visual Model|Java Virtual Machine
2|MCQ|E|R|1|Which keyword is used to inherit a class in Java?|implements~extends~inherits~super|extends
2|MCQ|M|U|1|Method overloading is an example of|Run-time polymorphism~Compile-time polymorphism~Encapsulation~Abstraction only|Compile-time polymorphism
2|MCQ|M|U|1|Which of these cannot be instantiated?|Final class~Abstract class~Public class~Static nested class|Abstract class
2|MCQ|H|AN|1|If a class has a private constructor only, then|It can be inherited freely~Objects cannot be created outside the class~It must be abstract~It cannot have methods|Objects cannot be created outside the class
3|MCQ|E|R|1|Which block always runs whether or not an exception occurs?|try~catch~finally~throw|finally
3|MCQ|M|U|1|Which of these is a checked exception?|NullPointerException~ArithmeticException~IOException~ArrayIndexOutOfBoundsException|IOException
3|MCQ|M|AP|1|Which keyword is used to declare that a method may throw an exception?|throw~throws~catch~try|throws
4|MCQ|E|R|1|Which method starts a new thread?|run()~start()~init()~execute()|start()
4|MCQ|M|U|1|Which collection does NOT allow duplicate elements?|ArrayList~LinkedList~HashSet~Vector|HashSet
4|MCQ|M|U|1|The synchronized keyword is used to|Make a method faster~Allow only one thread at a time~Create a thread~Stop a thread|Allow only one thread at a time
5|MCQ|E|R|1|Which package contains Swing components?|java.awt~javax.swing~java.util~java.io|javax.swing
5|MCQ|M|U|1|Which listener handles button clicks?|MouseMotionListener~ActionListener~KeyListener~WindowListener|ActionListener
1|TF|E|R|1|Java is a platform independent language.||True
2|TF|M|U|1|Java supports multiple inheritance of classes.||False
3|TF|M|U|1|A try block can have more than one catch block.||True
4|TF|M|U|1|Vector is synchronized but ArrayList is not.||True
5|TF|E|R|1|JFrame is a top-level container in Swing.||True
1|TF|E|U|1|A Java program can run without the main method in a normal application.||False
1|FILL|E|R|1|The keyword used to create an object is ________.||new
2|FILL|M|U|1|The keyword used to call the parent class constructor is ________.||super
4|FILL|M|R|1|The interface used to create a thread without extending Thread is ________.||Runnable
5|FILL|E|R|1|The default layout manager of JFrame is ________.||BorderLayout
1|SHORT|E|U|5|Explain any five features of Java.||Simple, object-oriented, platform independent, secure, robust, multithreaded, portable, high performance with JIT.
1|SHORT|M|U|5|Differentiate between JDK, JRE and JVM.||JVM runs bytecode; JRE = JVM + libraries to run; JDK = JRE + compiler and tools to develop.
2|SHORT|M|U|5|What is a constructor? Explain its types with examples.||Special method with class name, no return type, runs on object creation. Default, parameterised, copy-style; constructor overloading.
2|SHORT|M|AN|5|Differentiate between an abstract class and an interface.||Abstract class: can have state, constructors, concrete methods, single inheritance. Interface: contract, constants, default/static methods, multiple implementation.
2|SHORT|H|AN|5|Differentiate between method overloading and method overriding with examples.||Overloading: same name, different parameters, same class, compile time. Overriding: same signature in subclass, run time, @Override.
3|SHORT|E|U|5|What is an exception? Explain try, catch and finally with an example.||Abnormal event at run time; try holds risky code, catch handles, finally always runs (closing resources).
3|SHORT|M|U|5|What is a package? How do you create and import a user-defined package?||Group of related classes; package statement at top, folder structure, import pkg.ClassName or pkg.*.
4|SHORT|M|U|5|Explain the life cycle of a thread with a diagram.||New, Runnable, Running, Blocked/Waiting, Terminated; start(), sleep(), wait(), notify().
4|SHORT|H|AN|5|Compare ArrayList and Vector. When would you choose each?||Both dynamic arrays; Vector synchronized and slower, grows 100%; ArrayList not synchronized, grows 50%; use ArrayList normally, Vector for legacy thread-safe code.
5|SHORT|M|U|5|Explain the delegation event model in Java.||Source generates event, listener registered with source handles it; e.g. button.addActionListener(this) and actionPerformed().
1|LONG|M|U|10|Explain the structure of a Java program and how it is compiled and executed. Explain the role of the JVM and bytecode.||Class, main method, statements; javac makes .class bytecode; JVM class loader, verifier, interpreter/JIT; write once run anywhere.
2|LONG|M|U|10|Explain the four pillars of OOP (encapsulation, inheritance, polymorphism, abstraction) with Java examples.||Definition and code example for each.
2|LONG|H|C|10|Design a class hierarchy for a library (Item, Book, Magazine) showing inheritance, method overriding and an abstract method. Write the Java code.||Abstract Item with abstract getLoanDays(); Book and Magazine extend and override; demo in main using polymorphism.
3|LONG|H|AN|10|Explain checked and unchecked exceptions. Write a program with a user-defined exception InvalidAgeException.||Difference with examples; class InvalidAgeException extends Exception; throw when age < 18; handled in main.
4|LONG|H|AP|10|What is synchronization? Write a Java program where two threads update a shared counter safely.||Race condition, synchronized method or block, program with two threads and join(), correct final count.
5|LONG|M|AP|10|Write a Swing program with a text field, a button and a label. When the button is clicked, show the square of the number typed.||JFrame, JTextField, JButton, JLabel, ActionListener, Integer.parseInt, handle NumberFormatException.
""";

    // ================================================================== CSIT213 Database Management Systems
    static final String DBMS = """
1|MCQ|E|R|1|DBMS stands for|Data Backup Management System~Database Management System~Digital Base Machine System~Data Binary Model System|Database Management System
1|MCQ|M|U|1|In an ER diagram, a relationship is shown by a|Rectangle~Ellipse~Diamond~Double line only|Diamond
1|MCQ|M|U|1|A weak entity is one that|Has no attributes~Cannot be identified by its own attributes alone~Has only one row~Has no relationships|Cannot be identified by its own attributes alone
2|MCQ|E|R|1|Which SQL command removes all rows but keeps the table structure?|DROP~DELETE TABLE~TRUNCATE~ALTER|TRUNCATE
2|MCQ|M|AP|1|Which clause filters groups after GROUP BY?|WHERE~HAVING~ORDER BY~LIMIT|HAVING
2|MCQ|M|U|1|A foreign key is used to|Speed up queries~Link two tables~Encrypt data~Store images|Link two tables
2|MCQ|H|AN|1|A query with LEFT JOIN returns|Only matching rows~All rows of the left table and matching rows of the right~All rows of the right table only~Only non-matching rows|All rows of the left table and matching rows of the right
3|MCQ|M|U|1|A table is in 2NF if it is in 1NF and has no|Transitive dependency~Partial dependency~Multivalued attribute~Primary key|Partial dependency
3|MCQ|H|AN|1|BCNF requires that for every functional dependency X -> Y, X is a|Foreign key~Super key~Non-key attribute~Candidate attribute only|Super key
4|MCQ|E|R|1|ACID stands for Atomicity, Consistency, Isolation and|Durability~Dependency~Distribution~Data|Durability
4|MCQ|M|U|1|Which problem happens when a transaction reads data written by an uncommitted transaction?|Lost update~Dirty read~Phantom read~Deadlock only|Dirty read
4|MCQ|H|AN|1|Two-phase locking guarantees|Deadlock freedom~Conflict serializability~No rollbacks~Faster reads|Conflict serializability
1|TF|E|R|1|A primary key can contain NULL values.||False
2|TF|M|U|1|DELETE is a DDL command.||False
3|TF|M|U|1|Normalization reduces data redundancy.||True
4|TF|E|R|1|COMMIT makes the changes of a transaction permanent.||True
1|SHORT|E|U|5|What is a DBMS? Write its advantages over the file system.||Software to store and manage data; less redundancy, consistency, sharing, security, backup, concurrent access, query language.
1|SHORT|M|U|5|Explain the three-level (ANSI-SPARC) architecture of a DBMS.||External (views), conceptual (logical schema), internal (storage); data independence between levels.
2|SHORT|M|AP|5|Explain DDL, DML and DCL commands with two examples each.||DDL: CREATE, ALTER, DROP. DML: SELECT, INSERT, UPDATE, DELETE. DCL: GRANT, REVOKE.
2|SHORT|M|U|5|Explain the different types of keys: super key, candidate key, primary key and foreign key.||Definitions with a Student table example.
3|SHORT|M|U|5|What is functional dependency? Explain with an example.||X -> Y means a value of X decides one value of Y; e.g. RollNo -> Name.
3|SHORT|H|AN|5|Explain 3NF with an example of a table that is in 2NF but not in 3NF.||Remove transitive dependency; e.g. Student(Roll, Dept, HOD) with Dept -> HOD; split into two tables.
4|SHORT|M|U|5|Explain the ACID properties of a transaction.||Atomicity all or nothing, consistency valid state, isolation no interference, durability survives crash.
4|SHORT|H|AN|5|What is a deadlock in DBMS? Explain wait-die and wound-wait schemes.||Cycle of waiting transactions; timestamp-based schemes: older waits/younger dies vs older wounds/younger waits.
1|LONG|M|AP|10|Draw an ER diagram for a college database with Student, Course, Faculty and Department. Show keys, cardinality and participation.||Entities, attributes, primary keys, relationships enrols (M:N), teaches, belongs_to with cardinalities.
2|LONG|M|AP|10|Write SQL queries for an Employee(id, name, dept, salary) table: create the table, insert rows, find the highest salary per department, and list employees earning more than the average.||CREATE TABLE ..., INSERT ..., SELECT dept, MAX(salary) ... GROUP BY dept, subquery with AVG.
3|LONG|H|AN|10|Explain 1NF, 2NF, 3NF and BCNF with examples. Normalize a sample table step by step.||Definition and example of each, decomposition steps.
4|LONG|H|EV|10|Explain serializability. Differentiate between conflict and view serializability and test a given schedule using a precedence graph.||Serial vs serializable, conflicting operations, precedence graph, cycle means not conflict serializable.
4|LONG|M|U|10|Explain lock-based concurrency control. Describe shared and exclusive locks and the two-phase locking protocol.||Lock compatibility, growing and shrinking phase, strict 2PL, deadlock possibility.
3|NUM|M|AP|10|Given R(A, B, C, D) with FDs A -> B, B -> C, C -> D, find the candidate key and the highest normal form of R. Decompose it into 3NF.||Key = A; 2NF holds, transitive dependencies break 3NF; 3NF: (A,B), (B,C), (C,D).
3|NUM|H|AN|10|Given R(A, B, C, D, E) with FDs AB -> C, C -> D, D -> E, find the closure of AB and all candidate keys.||AB+ = ABCDE; candidate key AB.
""";

    // ================================================================== CSIT225 Computer Networks
    static final String CN = """
1|MCQ|E|R|1|How many layers does the OSI model have?|5~6~7~4|7
1|MCQ|E|R|1|Which device works at the network layer?|Hub~Switch~Router~Repeater|Router
1|MCQ|M|U|1|Which topology connects every device to a central device?|Bus~Ring~Star~Mesh|Star
2|MCQ|M|U|1|CSMA/CD is used in|Wi-Fi~Classic Ethernet~Token ring~Bluetooth|Classic Ethernet
2|MCQ|M|AP|1|Which error-detecting method uses polynomial division?|Parity~Checksum~CRC~Hamming distance only|CRC
2|MCQ|E|R|1|A MAC address is|16 bits~32 bits~48 bits~128 bits|48 bits
3|MCQ|M|AP|1|How many usable host addresses are in a /26 subnet?|62~64~30~126|62
3|MCQ|E|R|1|An IPv6 address is how many bits long?|32~64~128~256|128
3|MCQ|H|AN|1|Which routing algorithm uses Dijkstra's shortest path?|Distance vector~Link state~Flooding~Static only|Link state
4|MCQ|E|R|1|HTTP normally uses port|21~25~80~53|80
4|MCQ|M|U|1|Which protocol gives reliable, connection-oriented delivery?|UDP~TCP~IP~ICMP|TCP
4|MCQ|M|U|1|DNS is used to|Assign IP addresses~Translate names to IP addresses~Send email~Encrypt traffic|Translate names to IP addresses
1|TF|E|U|1|A switch works at the data link layer.||True
2|TF|M|U|1|Parity check can detect all two-bit errors.||False
3|TF|E|R|1|192.168.1.10 is a private IP address.||True
4|TF|M|U|1|UDP performs flow control.||False
1|SHORT|E|U|5|Compare LAN, MAN and WAN.||Area covered, speed, ownership, cost, examples.
1|SHORT|M|U|5|Compare the OSI and TCP/IP models.||7 vs 4 layers, mapping of layers, OSI is a reference model, TCP/IP is used in practice.
2|SHORT|M|U|5|Explain the stop-and-wait protocol with a diagram.||Send one frame, wait for ACK, timer and retransmit; simple but low efficiency.
2|SHORT|H|AN|5|Compare Go-Back-N and Selective Repeat ARQ.||Window sizes, what is resent after an error, receiver buffer, efficiency.
3|SHORT|M|AP|5|What is subnetting? Explain with an example.||Dividing a network into smaller ones by borrowing host bits; e.g. 192.168.1.0/24 into four /26.
3|SHORT|M|U|5|Differentiate between IPv4 and IPv6.||32 vs 128 bits, header, no NAT needed, built-in IPsec, autoconfiguration, notation.
4|SHORT|M|U|5|Explain the TCP three-way handshake.||SYN, SYN-ACK, ACK with sequence numbers; connection set up before data.
4|SHORT|E|U|5|Differentiate between TCP and UDP.||Connection, reliability, ordering, speed, header size, uses.
1|LONG|M|U|10|Explain all seven layers of the OSI model with the functions of each layer.||Physical to application; function and example protocols of each layer.
2|LONG|H|AN|10|Explain Hamming code for error correction. Find the Hamming code for the data 1011 and show how a single-bit error is corrected.||Parity bit positions 1,2,4; code word 0110011; error position found from parity checks.
3|LONG|H|AN|10|Explain distance vector and link state routing. Compare them and explain the count-to-infinity problem.||Bellman-Ford vs Dijkstra, exchange of tables vs LSAs, convergence, count-to-infinity and fixes.
4|LONG|M|U|10|Explain DNS, HTTP, FTP and SMTP protocols with their working and port numbers.||Working of each; ports 53, 80/443, 20-21, 25.
4|LONG|H|EV|10|Explain TCP congestion control (slow start, congestion avoidance, fast retransmit). Why is it needed?||cwnd growth, threshold, multiplicative decrease, triple duplicate ACK.
3|NUM|M|AP|10|Divide 192.168.10.0/24 into 4 equal subnets. Give the network address, broadcast address and usable range of each.||/26: .0-.63, .64-.127, .128-.191, .192-.255; usable .1-.62 etc.
3|NUM|H|AP|10|An organisation needs subnets for 100, 50 and 20 hosts from 172.16.0.0/24. Use VLSM to allocate them.||/25 for 100 (.0-.127), /26 for 50 (.128-.191), /27 for 20 (.192-.223).
2|NUM|M|AP|10|Compute the CRC for data 1101011011 using generator 10011. Show the transmitted frame.||Remainder 1110; transmitted frame 11010110111110.
""";
}
