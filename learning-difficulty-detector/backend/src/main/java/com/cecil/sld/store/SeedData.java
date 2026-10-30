package com.cecil.sld.store;

import com.cecil.sld.model.*;
import com.cecil.sld.util.Passwords;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Demo data for the first run: three B.Sc. IT subjects with 20 topics and 100
 * questions, 24 students, and about ten weeks of quiz history.
 *
 * The history is made with a simple "student model": each student has an
 * ability, each topic a difficulty, and a weak prerequisite makes the next
 * topic harder too. So the demo shows real patterns (Deadlocks, Subnetting,
 * Virtual Memory and Graphs come out hard; weak Paging leads to weak Virtual
 * Memory). Everything is made up. The random seed is fixed so it is always the same.
 */
public final class SeedData {
    private SeedData() {}

    public static void fill(DataStore db) {
        Random rnd = new Random(2025);
        LocalDate today = db.today();
        String created = today.minusDays(80).atTime(9, 0).toString();

        // ---------------------------------------------------------- users
        user(db, "admin", "Admin Office", Role.ADMIN, "admin123", "", "", created);
        user(db, "neha", "Dr. Neha Sinha", Role.TEACHER, "teacher123", "", "", created);
        user(db, "arvind", "Prof. Arvind Kujur", Role.TEACHER, "teacher123", "", "", created);
        String[] names = {
                "Riya Kumari", "Karan Mahto", "Ananya Oraon", "Rohit Singh", "Sneha Tirkey", "Aditya Prasad",
                "Pooja Munda", "Vikash Yadav", "Nisha Ekka", "Saurabh Gupta", "Tanya Lakra", "Manish Soren",
                "Priya Hembrom", "Abhishek Rai", "Kajal Minz", "Rahul Bhagat", "Divya Toppo", "Amit Mahato",
                "Shreya Sahu", "Nikhil Barla", "Megha Kerketta", "Suraj Pandey", "Anjali Xalxo", "Deepak Horo"};
        List<User> students = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            String first = names[i].split(" ")[0].toLowerCase();
            String batch = i < 12 ? "BSc IT 2025 - A" : "BSc IT 2025 - B";
            String roll = String.format("2025-BSCIT/%03d%s", i < 12 ? i + 1 : i - 11, i < 12 ? "A" : "B");
            students.add(user(db, first, names[i], Role.STUDENT, "student123", roll, batch, created));
        }

        // ---------------------------------------------------------- subjects and topics
        Subject os = subject(db, "CSIT135", "Operating Systems", "river", "Processes, scheduling, synchronisation, deadlocks and memory.");
        Subject ds = subject(db, "DS-201", "Data Structures", "moss", "Arrays, lists, stacks, queues, trees, graphs and sorting.");
        Subject cn = subject(db, "CN-202", "Computer Networks", "clay", "Network models, IP addressing, routing, transport and application layers.");

        Topic osBasics = topic(db, os, "OS Basics & System Calls", 1, 1, 45, "Draw the user-mode / kernel-mode boundary and mark where each system call crosses it.");
        Topic proc = topic(db, os, "Processes & Threads", 1, 2, 55, "Learn the 5-state diagram by drawing it from memory.", osBasics);
        Topic sched = topic(db, os, "CPU Scheduling", 2, 1, 90, "Always draw the Gantt chart first, then compute waiting time.", proc);
        Topic sync = topic(db, os, "Process Synchronization", 2, 2, 80, "For every solution, check the three rules: mutual exclusion, progress, bounded waiting.", proc);
        Topic dead = topic(db, os, "Deadlocks", 3, 1, 95, "Practise Banker's algorithm with small tables: Need = Max - Allocation.", sync);
        Topic paging = topic(db, os, "Memory Management & Paging", 3, 2, 75, "Split a logical address into page number and offset on paper.", osBasics);
        Topic vm = topic(db, os, "Virtual Memory & Page Replacement", 4, 1, 100, "Trace FIFO, LRU and Optimal on the same reference string side by side.", paging);

        Topic arr = topic(db, ds, "Arrays & Complexity", 1, 1, 50, "Count the loops: one loop over n is O(n), a loop inside a loop is O(n^2).");
        Topic stack = topic(db, ds, "Stacks & Queues", 1, 2, 55, "Act out push/pop with a pile of books.", arr);
        Topic list = topic(db, ds, "Linked Lists", 2, 1, 70, "Draw boxes and arrows for every pointer change.", arr);
        Topic rec = topic(db, ds, "Recursion", 2, 2, 80, "Write the base case first, then trust the smaller call.");
        Topic tree = topic(db, ds, "Trees & BST", 3, 1, 85, "Do the three traversals on the same small tree until they are automatic.", list, rec);
        Topic graph = topic(db, ds, "Graphs (BFS & DFS)", 3, 2, 95, "BFS uses a queue, DFS uses a stack (or recursion) - trace both on one graph.", tree, stack);
        Topic sort = topic(db, ds, "Sorting & Searching", 4, 1, 70, "Learn which sorts are stable and their best/worst cases in one table.", arr);

        Topic models = topic(db, cn, "OSI & TCP/IP Models", 1, 1, 45, "Remember the layers with a sentence: Please Do Not Throw Sausage Pizza Away.");
        Topic subnet = topic(db, cn, "IP Addressing & Subnetting", 2, 1, 120, "Write the binary of the last octet; the block size is 256 minus the mask octet.", models);
        Topic routing = topic(db, cn, "Routing", 2, 2, 85, "Compare distance-vector and link-state in a two-column table.", subnet);
        Topic transport = topic(db, cn, "TCP & UDP", 3, 1, 65, "Draw the 3-way handshake with SYN, SYN-ACK, ACK arrows.", models);
        Topic app = topic(db, cn, "Application Layer (DNS, HTTP)", 3, 2, 55, "Follow one web request from typing a URL to seeing the page.", transport);
        Topic sec = topic(db, cn, "Network Security Basics", 4, 1, 60, "Match each attack to the security goal it breaks (confidentiality, integrity, availability).", models);

        // ---------------------------------------------------------- questions
        Questions q = new Questions(db);
        // OS basics
        q.add(osBasics, 1, "Which program manages hardware and gives services to other programs?", 1, "It starts when the computer boots.", "The operating system manages hardware and provides services to applications.", "Compiler", "Operating system", "Text editor", "Web browser");
        q.add(osBasics, 1, "How does a user program ask the kernel for a service such as reading a file?", 2, "It is a special kind of call.", "A system call is the doorway from user mode into kernel mode.", "By a jump instruction", "By an interrupt vector table", "By a system call", "By a function pointer");
        q.add(osBasics, 2, "In which mode can privileged instructions run?", 0, "Think about who should control hardware.", "Only kernel mode may run privileged instructions; user mode cannot.", "Kernel mode", "User mode", "Both modes", "Neither mode");
        q.add(osBasics, 2, "Which of these is NOT a usual system call?", 3, "System calls ask the kernel for help.", "Adding two numbers needs no kernel help; it runs in user mode.", "fork()", "open()", "read()", "add(a, b)");
        q.add(osBasics, 3, "A microkernel moves most services into user space mainly to get:", 1, "Think about what happens when a driver crashes.", "With services in user space, a crash does not bring down the kernel - better reliability (at some speed cost).", "More speed", "More reliability and easier extension", "Smaller programs", "No need for system calls");
        // processes
        q.add(proc, 1, "A program that is running is called a:", 0, "It has a PCB.", "A process is a program in execution.", "Process", "File", "Thread pool", "Kernel");
        q.add(proc, 1, "Which state does a process go to when it waits for I/O?", 2, "It cannot run until the I/O finishes.", "Waiting (blocked) is the state while waiting for an event such as I/O.", "Ready", "Running", "Waiting", "Terminated");
        q.add(proc, 2, "What do threads of the same process share?", 1, "Each thread has its own stack.", "Threads share code, data and open files but each has its own registers and stack.", "Stack and registers", "Code, data and open files", "Nothing", "Only the program counter");
        q.add(proc, 2, "Where does the OS keep the saved registers and state of a process?", 3, "It is a block of information.", "The Process Control Block (PCB) stores the state of each process.", "Page table", "TLB", "Stack only", "Process Control Block");
        q.add(proc, 3, "After fork() is called once in a program, how many processes are running that code?", 1, "The parent continues too.", "fork() creates one child; parent + child = 2 processes.", "1", "2", "3", "4");
        // scheduling
        q.add(sched, 1, "Which scheduling algorithm runs processes in the order they arrive?", 0, "First come ...", "FCFS runs jobs in arrival order.", "FCFS", "SJF", "Round Robin", "Priority");
        q.add(sched, 2, "Round Robin uses a fixed:", 2, "Each process gets a turn of this length.", "Round Robin gives each process a time quantum in turn.", "Priority", "Burst size", "Time quantum", "Queue length");
        q.add(sched, 2, "Which algorithm gives the minimum average waiting time (when bursts are known)?", 1, "Shorter jobs first help everybody.", "SJF is optimal for average waiting time.", "FCFS", "SJF", "Round Robin", "LIFO");
        q.add(sched, 3, "P1=24, P2=3, P3=3 ms arrive at time 0 in that order. Average waiting time with FCFS?", 3, "Waiting times are 0, 24 and 27.", "(0 + 24 + 27) / 3 = 17 ms.", "3 ms", "10 ms", "13 ms", "17 ms");
        q.add(sched, 3, "Starvation in priority scheduling is solved by:", 0, "Old processes get more important.", "Aging slowly raises the priority of waiting processes.", "Aging", "Paging", "Swapping", "Thrashing");
        // sync
        q.add(sync, 1, "The part of code that uses shared data is called the:", 1, "It must be protected.", "The critical section is the code that accesses shared data.", "Entry section", "Critical section", "Remainder section", "Exit code");
        q.add(sync, 2, "When the result depends on the order in which threads run, it is a:", 2, "Threads 'race' each other.", "A race condition happens when the outcome depends on timing.", "Deadlock", "Starvation", "Race condition", "Context switch");
        q.add(sync, 2, "A semaphore wait() operation (P) does what?", 0, "It may block.", "wait() decrements the semaphore and blocks if it becomes negative.", "Decrements and may block", "Increments and may wake a process", "Only reads the value", "Resets it to zero");
        q.add(sync, 3, "Which is NOT one of the three requirements for a critical-section solution?", 3, "Mutual exclusion, progress, bounded waiting...", "The three rules are mutual exclusion, progress and bounded waiting - speed is not one of them.", "Mutual exclusion", "Progress", "Bounded waiting", "Fastest execution");
        q.add(sync, 3, "A binary semaphore is also commonly used as a:", 1, "Lock / unlock.", "A binary semaphore (0/1) works like a mutex lock.", "Counter", "Mutex lock", "Monitor", "Pipe");
        // deadlocks
        q.add(dead, 1, "How many conditions must hold at the same time for a deadlock?", 2, "Mutual exclusion, hold and wait, ...", "All four Coffman conditions: mutual exclusion, hold and wait, no preemption, circular wait.", "2", "3", "4", "5");
        q.add(dead, 2, "Banker's algorithm is used for deadlock:", 1, "It checks a safe state before giving resources.", "Banker's algorithm avoids deadlock by only granting requests that keep a safe state.", "Detection", "Avoidance", "Prevention", "Recovery");
        q.add(dead, 2, "Ordering resources and requesting them in increasing order breaks which condition?", 3, "Can a cycle form if everyone asks in the same order?", "A fixed order makes a cycle impossible, so circular wait is broken.", "Mutual exclusion", "Hold and wait", "No preemption", "Circular wait");
        q.add(dead, 3, "In Banker's algorithm, Need is calculated as:", 0, "What else might it ask for?", "Need = Max - Allocation.", "Max - Allocation", "Allocation - Max", "Available - Max", "Max + Available");
        q.add(dead, 3, "If the system is in an unsafe state, then:", 2, "Unsafe is not the same as deadlocked.", "Unsafe means a deadlock is possible, not certain.", "Deadlock has already happened", "Deadlock can never happen", "Deadlock may happen", "All processes stop");
        // paging
        q.add(paging, 1, "Paging divides physical memory into fixed-size blocks called:", 1, "Pages go into them.", "Physical memory blocks are frames; logical blocks are pages.", "Pages", "Frames", "Segments", "Sectors");
        q.add(paging, 2, "Paging removes which kind of fragmentation?", 0, "Any free frame can be used.", "Paging removes external fragmentation (internal can remain in the last page).", "External", "Internal", "Both", "Neither");
        q.add(paging, 2, "The TLB is used to:", 2, "It is a small fast cache.", "The TLB caches page-table entries to speed up address translation.", "Store files", "Hold the PCB", "Speed up address translation", "Swap pages to disk");
        q.add(paging, 3, "Page size 4 KB, logical address 8200. What is the page number?", 1, "4 KB = 4096 bytes.", "8200 / 4096 = 2 (offset 8).", "1", "2", "3", "8");
        q.add(paging, 3, "With a 32-bit logical address and 4 KB pages, how many bits are the offset?", 3, "2^12 = 4096.", "4 KB = 2^12, so the offset is 12 bits.", "8", "10", "20", "12");
        // virtual memory
        q.add(vm, 1, "When a needed page is not in memory, it causes a:", 0, "The OS must fetch it.", "A page fault tells the OS to bring the page from disk.", "Page fault", "Segmentation fault", "Deadlock", "Context switch");
        q.add(vm, 2, "Which page replacement algorithm gives the fewest page faults (in theory)?", 2, "It needs to see the future.", "Optimal (OPT) replaces the page used farthest in the future.", "FIFO", "LRU", "Optimal", "Random");
        q.add(vm, 2, "Belady's anomaly can happen with:", 0, "More frames, more faults - for one algorithm.", "FIFO can show more faults with more frames (Belady's anomaly).", "FIFO", "LRU", "Optimal", "None of these");
        q.add(vm, 3, "When a system spends more time swapping pages than running, it is called:", 3, "The disk light never stops.", "Thrashing: too little memory per process causes constant page faults.", "Paging", "Spooling", "Caching", "Thrashing");
        q.add(vm, 3, "Reference string 1,2,3,1,4 with 3 frames and FIFO. How many page faults?", 1, "1,2,3 fault; 1 hits; 4 replaces the oldest.", "Faults: 1,2,3,4 = 4 (the second 1 is a hit).", "3", "4", "5", "2");
        // arrays
        q.add(arr, 1, "Accessing arr[i] in an array takes:", 0, "The address is calculated directly.", "Index access is O(1).", "O(1)", "O(n)", "O(log n)", "O(n^2)");
        q.add(arr, 1, "Array indexes in Java start at:", 1, "The first element...", "Java arrays start at index 0.", "1", "0", "-1", "Any number");
        q.add(arr, 2, "Two nested loops each running n times have complexity:", 2, "n times n.", "n x n = O(n^2).", "O(n)", "O(2n)", "O(n^2)", "O(log n)");
        q.add(arr, 2, "Inserting at the beginning of an array of n elements takes:", 3, "Everything must move.", "All n elements shift one place, so O(n).", "O(1)", "O(log n)", "O(n log n)", "O(n)");
        q.add(arr, 3, "Which grows the slowest as n becomes large?", 0, "Halving each time.", "O(log n) grows slowest of these.", "O(log n)", "O(n)", "O(n log n)", "O(sqrt n * n)");
        // stacks & queues
        q.add(stack, 1, "A stack works in which order?", 1, "Think of a pile of plates.", "Stack = Last In, First Out.", "FIFO", "LIFO", "Random", "Sorted");
        q.add(stack, 1, "A queue works in which order?", 0, "Think of a ticket line.", "Queue = First In, First Out.", "FIFO", "LIFO", "Priority only", "Random");
        q.add(stack, 2, "Which data structure is used to check balanced brackets?", 2, "The last open bracket must close first.", "A stack matches the most recent open bracket.", "Queue", "Array list", "Stack", "Heap");
        q.add(stack, 2, "Postfix of (A + B) * C is:", 3, "Operands first, operator after.", "(A+B)*C becomes A B + C *.", "A + B * C", "* + A B C", "A B C + *", "A B + C *");
        q.add(stack, 3, "In a circular queue of size n, the queue is full when:", 1, "One slot is kept empty in the usual method.", "Full when (rear + 1) % n == front.", "rear == front", "(rear + 1) % n == front", "rear == n", "front == 0");
        // linked lists
        q.add(list, 1, "Each node of a singly linked list stores data and:", 0, "It points somewhere.", "A node stores data and a reference to the next node.", "A link to the next node", "Its index", "The list size", "A link to the head only");
        q.add(list, 2, "Finding the k-th element of a linked list takes:", 2, "There is no direct index.", "You must walk from the head: O(k), i.e. O(n) worst case.", "O(1)", "O(log n)", "O(n)", "O(n^2)");
        q.add(list, 2, "Inserting a new node at the head of a singly linked list takes:", 1, "Only two links change.", "New node points to old head; head points to new node: O(1).", "O(n)", "O(1)", "O(log n)", "O(n log n)");
        q.add(list, 3, "To delete a node in a singly linked list, you need a reference to:", 3, "Someone's 'next' must change.", "You need the previous node so you can change its next link.", "The head only", "The next node", "The tail", "The previous node");
        q.add(list, 3, "Floyd's slow and fast pointer method is used to:", 0, "Tortoise and hare.", "It detects a cycle (loop) in a linked list.", "Detect a cycle", "Sort the list", "Reverse the list", "Find the length in O(1)");
        // recursion
        q.add(rec, 1, "A recursive function must have a:", 2, "Otherwise it never stops.", "The base case stops the recursion.", "Loop", "Global variable", "Base case", "Return type of int");
        q.add(rec, 2, "What does a recursion without a base case usually cause?", 1, "Calls keep piling up.", "Calls keep growing the call stack until StackOverflowError.", "Compile error", "Stack overflow", "Infinite loop with no memory use", "Nothing");
        q.add(rec, 2, "fact(n) = n * fact(n-1), fact(0)=1. What is fact(4)?", 3, "4 x 3 x 2 x 1.", "4! = 24.", "4", "12", "16", "24");
        q.add(rec, 3, "Naive recursive Fibonacci has time complexity about:", 0, "Each call makes two calls.", "It grows like O(2^n) because of repeated sub-calls.", "O(2^n)", "O(n)", "O(n log n)", "O(log n)");
        q.add(rec, 3, "Which data structure does the computer use to manage recursive calls?", 2, "Last call finishes first.", "The call stack holds each active call.", "Queue", "Heap", "Stack", "Hash table");
        // trees
        q.add(tree, 1, "The top node of a tree is called the:", 0, "It has no parent.", "The root has no parent.", "Root", "Leaf", "Child", "Branch");
        q.add(tree, 2, "Inorder traversal of a BST gives the keys in:", 1, "Left, root, right.", "Inorder on a BST visits keys in ascending order.", "Random order", "Ascending order", "Descending order", "Level order");
        q.add(tree, 2, "Preorder traversal visits nodes in which order?", 2, "'Pre' - the root comes first.", "Preorder = Root, Left, Right.", "Left, Root, Right", "Left, Right, Root", "Root, Left, Right", "Right, Root, Left");
        q.add(tree, 3, "The worst-case search time in an unbalanced BST with n nodes is:", 3, "It can look like a linked list.", "A skewed BST is like a list: O(n).", "O(1)", "O(log n)", "O(n log n)", "O(n)");
        q.add(tree, 3, "Maximum number of nodes in a binary tree of height h (root at height 0):", 1, "1 + 2 + 4 + ...", "1 + 2 + ... + 2^h = 2^(h+1) - 1.", "2^h", "2^(h+1) - 1", "h^2", "2h + 1");
        // graphs
        q.add(graph, 1, "BFS uses which data structure?", 0, "Visit level by level.", "BFS uses a queue.", "Queue", "Stack", "Heap", "Tree");
        q.add(graph, 2, "DFS can be done with recursion or with a:", 2, "Go deep, then come back.", "DFS uses a stack (the call stack in recursion).", "Queue", "Priority queue", "Stack", "Linked list");
        q.add(graph, 2, "An adjacency matrix for V vertices needs memory of:", 1, "A V by V table.", "A V x V matrix needs O(V^2).", "O(V)", "O(V^2)", "O(E)", "O(V + E)");
        q.add(graph, 3, "Time complexity of BFS with an adjacency list is:", 3, "Every vertex and every edge once.", "BFS visits each vertex and edge once: O(V + E).", "O(V^2)", "O(E log V)", "O(V * E)", "O(V + E)");
        q.add(graph, 3, "Which traversal finds the shortest path (fewest edges) in an unweighted graph?", 0, "Level by level from the start.", "BFS finds the fewest-edge path in an unweighted graph.", "BFS", "DFS", "Inorder", "Topological sort");
        // sorting
        q.add(sort, 1, "Binary search needs the list to be:", 1, "It halves the search each time.", "Binary search works only on sorted data.", "Short", "Sorted", "Linked", "Unique");
        q.add(sort, 2, "Average time of merge sort is:", 2, "Divide in halves, merge in linear time.", "Merge sort is O(n log n) in all cases.", "O(n)", "O(n^2)", "O(n log n)", "O(log n)");
        q.add(sort, 2, "Which sort is NOT stable in its usual form?", 3, "It swaps far-apart elements.", "Selection sort can change the order of equal items, so it is not stable.", "Merge sort", "Insertion sort", "Bubble sort", "Selection sort");
        q.add(sort, 3, "Worst case of quick sort happens when the pivot is always:", 0, "Very unbalanced partitions.", "Always picking the smallest or largest gives O(n^2).", "The smallest or largest element", "The median", "A random element", "The middle element");
        q.add(sort, 3, "Binary search on 1024 sorted items needs at most about how many comparisons?", 1, "2^10 = 1024.", "log2(1024) = 10 (plus one).", "1024", "11", "32", "512");
        // models
        q.add(models, 1, "How many layers does the OSI model have?", 2, "Please Do Not Throw Sausage Pizza Away.", "OSI has 7 layers.", "4", "5", "7", "8");
        q.add(models, 1, "Which layer does routing between networks?", 1, "IP lives here.", "The Network layer (layer 3) routes packets.", "Data link", "Network", "Transport", "Session");
        q.add(models, 2, "MAC addresses are used at which layer?", 0, "Layer 2.", "MAC addresses belong to the Data link layer.", "Data link", "Network", "Transport", "Physical");
        q.add(models, 2, "The TCP/IP model has how many layers (common textbook version)?", 3, "Application, Transport, Internet, ...", "Application, Transport, Internet, Network Access = 4.", "7", "5", "3", "4");
        q.add(models, 3, "The data unit at the Transport layer (TCP) is called a:", 1, "Frame is layer 2, packet is layer 3.", "TCP data units are segments.", "Frame", "Segment", "Bit", "Packet");
        // subnetting
        q.add(subnet, 1, "How many bits are in an IPv4 address?", 2, "Four octets.", "4 x 8 = 32 bits.", "16", "64", "32", "128");
        q.add(subnet, 2, "What is the subnet mask for /24?", 0, "24 ones, then zeros.", "/24 = 255.255.255.0.", "255.255.255.0", "255.255.0.0", "255.255.255.128", "255.0.0.0");
        q.add(subnet, 2, "How many usable host addresses are in a /26 subnet?", 1, "2^(32-26), minus 2.", "2^6 = 64, minus network and broadcast = 62.", "64", "62", "30", "126");
        q.add(subnet, 3, "Which subnet does 192.168.10.77/27 belong to?", 3, "Block size for /27 is 32.", "Blocks: 0, 32, 64, 96... 77 is in 64 to 95, so 192.168.10.64.", "192.168.10.32", "192.168.10.0", "192.168.10.96", "192.168.10.64");
        q.add(subnet, 3, "What is the broadcast address of 10.0.0.0/30?", 0, "A /30 has 4 addresses.", "10.0.0.0 to 10.0.0.3; the last one is the broadcast.", "10.0.0.3", "10.0.0.1", "10.0.0.4", "10.0.0.255");
        // routing
        q.add(routing, 1, "A device that forwards packets between different networks is a:", 1, "It reads IP addresses.", "A router connects networks and forwards packets.", "Hub", "Router", "Repeater", "Switch");
        q.add(routing, 2, "RIP chooses the best path using:", 0, "It counts routers on the way.", "RIP uses hop count (max 15).", "Hop count", "Bandwidth", "Delay only", "Cost set by admin");
        q.add(routing, 2, "OSPF is a:", 2, "Each router knows the whole map.", "OSPF is a link-state protocol using Dijkstra's algorithm.", "Distance-vector protocol", "Path-vector protocol", "Link-state protocol", "Static route");
        q.add(routing, 3, "The 'count to infinity' problem affects:", 3, "Routers only know what neighbours tell them.", "Distance-vector protocols like RIP suffer from count to infinity.", "Link-state protocols", "Static routing", "Default routes", "Distance-vector protocols");
        q.add(routing, 3, "Which route is chosen when several match a destination?", 1, "More specific wins.", "The longest prefix match (most specific) is used.", "The shortest prefix", "The longest prefix match", "The first one added", "A random one");
        // transport
        q.add(transport, 1, "Which protocol is connection-oriented?", 0, "It does a handshake.", "TCP sets up a connection first.", "TCP", "UDP", "IP", "ICMP");
        q.add(transport, 1, "Which protocol is better for live video calls?", 1, "Late data is useless.", "UDP has lower delay; small losses are fine for live media.", "TCP", "UDP", "FTP", "SMTP");
        q.add(transport, 2, "The TCP 3-way handshake is:", 2, "Synchronise, then acknowledge.", "SYN, SYN-ACK, ACK.", "ACK, SYN, FIN", "SYN, ACK, FIN", "SYN, SYN-ACK, ACK", "FIN, ACK, SYN");
        q.add(transport, 2, "Port numbers are used to identify:", 3, "One computer runs many programs.", "Ports identify the application process on a host.", "A computer", "A network", "A router", "An application process");
        q.add(transport, 3, "TCP flow control mainly uses the:", 0, "The receiver tells how much it can take.", "The receiver's advertised window controls how much the sender sends.", "Sliding window", "Checksum", "TTL", "Port number");
        // application
        q.add(app, 1, "DNS translates:", 1, "You type a name, the computer needs a number.", "DNS maps domain names to IP addresses.", "IP to MAC", "Domain names to IP addresses", "Ports to services", "HTTP to HTTPS");
        q.add(app, 1, "The default port of HTTPS is:", 2, "HTTP is 80.", "HTTPS uses port 443.", "80", "21", "443", "25");
        q.add(app, 2, "Which HTTP method is normally used to send form data that creates something?", 0, "GET just reads.", "POST sends data to create or change something.", "POST", "GET", "HEAD", "TRACE");
        q.add(app, 2, "HTTP status 404 means:", 3, "4xx is a client error.", "404 Not Found - the page does not exist.", "Server error", "Moved permanently", "OK", "Not found");
        q.add(app, 3, "Which protocol sends email between mail servers?", 1, "Simple Mail ...", "SMTP sends mail; POP3/IMAP read it.", "POP3", "SMTP", "IMAP", "SNMP");
        // security
        q.add(sec, 1, "A firewall mainly:", 0, "It decides what may pass.", "A firewall filters traffic using rules.", "Filters network traffic", "Speeds up the network", "Stores passwords", "Assigns IP addresses");
        q.add(sec, 2, "Flooding a server with requests so real users cannot use it is a:", 2, "Availability is attacked.", "A Denial of Service (DoS) attack breaks availability.", "Phishing attack", "Man-in-the-middle attack", "Denial of Service attack", "Brute force attack");
        q.add(sec, 2, "Encryption mainly protects:", 1, "Who can read it?", "Encryption protects confidentiality.", "Availability", "Confidentiality", "Speed", "Routing");
        q.add(sec, 3, "In public-key cryptography, a message encrypted with Bob's public key is decrypted with:", 3, "Only Bob should read it.", "Only Bob's private key can decrypt it.", "Bob's public key", "Alice's private key", "A shared password", "Bob's private key");
        q.add(sec, 3, "A hash function is mainly used to check:", 0, "Did the data change?", "Hashes check integrity: any change gives a different hash.", "Integrity", "Availability", "Routing", "Compression");

        // ---------------------------------------------------------- simulated history
        simulate(db, rnd, students, today);
    }

    // ================================================================= helpers

    private static User user(DataStore db, String username, String name, Role role, String password, String roll, String batch, String created) {
        User u = new User();
        u.id = db.nextId("U");
        u.username = username;
        u.name = name;
        u.role = role;
        u.passwordHash = Passwords.hash(password);
        u.rollNo = roll;
        u.batch = batch;
        u.createdAt = created;
        db.users.add(u);
        return u;
    }

    private static Subject subject(DataStore db, String code, String name, String color, String desc) {
        Subject s = new Subject();
        s.id = db.nextId("S");
        s.code = code;
        s.name = name;
        s.color = color;
        s.description = desc;
        db.subjects.add(s);
        return s;
    }

    private static Topic topic(DataStore db, Subject s, String name, int unit, int order, int expected, String tip, Topic... pre) {
        Topic t = new Topic();
        t.id = db.nextId("T");
        t.subjectId = s.id;
        t.name = name;
        t.unit = unit;
        t.order = order;
        t.expectedSeconds = expected;
        t.tip = tip;
        for (Topic p : pre) t.prerequisiteIds.add(p.id);
        db.topics.add(t);
        return t;
    }

    private static final class Questions {
        final DataStore db;
        Questions(DataStore db) { this.db = db; }

        void add(Topic t, int level, String text, int answer, String hint, String explanation, String... options) {
            Question q = new Question();
            q.id = db.nextId("Q");
            q.topicId = t.id;
            q.level = level;
            q.text = text;
            q.answer = answer;
            q.hint = hint;
            q.explanation = explanation;
            q.options = new ArrayList<>(List.of(options));
            db.questions.add(q);
        }
    }

    /** How hard each topic is for the class in general (higher = harder). */
    private static double baseDifficulty(String topicName) {
        return switch (topicName) {
            case "Deadlocks", "IP Addressing & Subnetting" -> 1.05;
            case "Virtual Memory & Page Replacement", "Graphs (BFS & DFS)" -> 0.85;
            case "Recursion", "Process Synchronization", "Routing" -> 0.55;
            case "CPU Scheduling", "Trees & BST", "Memory Management & Paging" -> 0.35;
            case "Linked Lists", "TCP & UDP", "Sorting & Searching" -> 0.1;
            default -> -0.3;
        };
    }

    /** Topics where many students hold a wrong idea (wrong answers given with confidence). */
    private static final Set<String> MISCONCEPTION_TOPICS = Set.of("Deadlocks", "IP Addressing & Subnetting", "Recursion");

    private static void simulate(DataStore db, Random rnd, List<User> students, LocalDate today) {
        Map<String, Double> mastery = new HashMap<>();                  // studentId|topicId -> 0..1
        Map<String, Double> drift = new HashMap<>();                    // per student-topic change over time
        Map<String, List<Topic>> topicsBySubject = new HashMap<>();
        for (Subject s : db.subjects) topicsBySubject.put(s.id, db.topicsOf(s.id));

        for (int si = 0; si < students.size(); si++) {
            User st = students.get(si);
            double ability = rnd.nextGaussian() * 0.75;
            if (si == 0) ability = 0.9;          // Riya: strong overall, one weak spot
            if (si == 1) ability = -0.6;         // Karan: struggles, weak prerequisites
            if (si == 13) ability = -0.9;        // Abhishek: needs a lot of help
            for (Subject s : db.subjects) {
                double subjectSkill = rnd.nextGaussian() * 0.45;
                for (Topic t : topicsBySubject.get(s.id)) {
                    double preEffect = 0;
                    for (String pid : t.prerequisiteIds) preEffect += (mastery.get(st.id + "|" + pid) - 0.55) * 1.6;
                    if (!t.prerequisiteIds.isEmpty()) preEffect /= t.prerequisiteIds.size();
                    double logit = 1.1 * ability + subjectSkill - 1.4 * baseDifficulty(t.name) + preEffect + rnd.nextGaussian() * 0.45 + 0.7;
                    if (si == 0 && t.name.startsWith("IP Addressing")) logit = -1.4;  // Riya's weak spot
                    double m = 1 / (1 + Math.exp(-logit));
                    mastery.put(st.id + "|" + t.id, m);
                    // most improve a little with practice; some forget (decline)
                    double d = rnd.nextDouble() < 0.12 ? -0.25 : 0.12 * rnd.nextDouble();
                    if (si == 5 && t.name.startsWith("Virtual")) d = -0.35; // Aditya: slipping on VM
                    drift.put(st.id + "|" + t.id, d);
                }
            }
        }

        for (int si = 0; si < students.size(); si++) {
            User st = students.get(si);
            for (Subject s : db.subjects) {
                List<Topic> topics = topicsBySubject.get(s.id);
                int tests = 2 + rnd.nextInt(2);
                int practices = 1 + rnd.nextInt(4);
                if (si == 13 && s.code.equals("CN-202")) { tests = 1; practices = 0; }   // Abhishek: barely tried CN
                List<Integer> days = new ArrayList<>();
                for (int i = 0; i < tests + practices; i++) days.add(2 + rnd.nextInt(68));
                days.sort(Comparator.reverseOrder());                    // oldest first
                int testsLeft = tests;
                for (int i = 0; i < days.size(); i++) {
                    int daysAgo = days.get(i);
                    boolean isTest = i == 0 || (testsLeft > 0 && rnd.nextBoolean()) || (days.size() - i <= testsLeft);
                    if (isTest) testsLeft--;
                    double progress = 1 - daysAgo / 70.0;                // 0 (old) .. 1 (recent)
                    List<Question> qs = isTest ? testQuestions(db, topics, rnd) : practiceQuestions(db, topics, st, mastery, rnd);
                    LocalDateTime finish = today.minusDays(daysAgo).atTime(9 + rnd.nextInt(12), rnd.nextInt(60));
                    Attempt a = new Attempt();
                    a.id = db.nextId("A");
                    a.studentId = st.id;
                    a.subjectId = s.id;
                    a.kind = isTest ? "TEST" : "PRACTICE";
                    int totalSec = 0;
                    for (Question q : qs) {
                        Topic t = db.topic(q.topicId);
                        String key = st.id + "|" + t.id;
                        double m = clamp(mastery.get(key) + drift.get(key) * progress, 0.03, 0.97);
                        double p = clamp(m + (q.level == 1 ? 0.15 : q.level == 3 ? -0.15 : 0), 0.02, 0.98);
                        AttemptItem it = new AttemptItem();
                        it.questionId = q.id;
                        it.topicId = t.id;
                        it.level = q.level;
                        it.correct = rnd.nextDouble() < p;
                        if (it.correct) it.chosen = q.answer;
                        else if (rnd.nextDouble() < 0.06) it.chosen = -1;
                        else {
                            // wrong answers cluster on one "attractive" wrong option
                            int attractive = (q.answer + 1) % q.options.size();
                            it.chosen = rnd.nextDouble() < 0.6 ? attractive : (q.answer + 1 + rnd.nextInt(q.options.size() - 1)) % q.options.size();
                        }
                        double slow = 1 + (1 - m) * 1.3;
                        it.seconds = (int) Math.max(5, Math.round(t.expectedSeconds * slow * Math.exp(rnd.nextGaussian() * 0.25)));
                        totalSec += it.seconds;
                        it.hintUsed = !isTest && rnd.nextDouble() < (1 - m) * 0.7;
                        if (it.correct) it.confidence = rnd.nextDouble() < m ? 3 : 2;
                        else if (MISCONCEPTION_TOPICS.contains(t.name) && m < 0.55) it.confidence = rnd.nextDouble() < 0.6 ? 3 : 2;
                        else it.confidence = rnd.nextDouble() < 0.12 ? 3 : (rnd.nextBoolean() ? 2 : 1);
                        a.items.add(it);
                        if (it.correct) a.score++;
                    }
                    a.total = a.items.size();
                    a.finishedAt = finish.toString();
                    a.startedAt = finish.minusSeconds(totalSec + 30).withNano(0).toString();
                    db.attempts.add(a);
                }
            }
        }
        db.attempts.sort(Comparator.comparing(a -> a.finishedAt));

        // students' own ratings ("this feels hard")
        for (int si = 0; si < students.size(); si++) {
            User st = students.get(si);
            for (Topic t : db.topics) {
                double m = mastery.get(st.id + "|" + t.id);
                if (rnd.nextDouble() < 0.18) {
                    SelfReport r = new SelfReport();
                    r.id = db.nextId("R");
                    r.studentId = st.id;
                    r.topicId = t.id;
                    r.rating = (int) Math.max(1, Math.min(5, Math.round(5 - m * 4 + rnd.nextGaussian() * 0.6)));
                    r.note = r.rating >= 4 ? pick(rnd, "I get confused in the numericals.", "Too many steps to remember.", "Did not understand the class.", "") : "";
                    r.date = today.minusDays(1 + rnd.nextInt(30)).toString();
                    db.reports.add(r);
                }
            }
        }

        // a few support plans made by teachers
        User neha = db.userByName("neha"), arvind = db.userByName("arvind");
        Object[][] plans = {
                {1, "Deadlocks", "Extra class", neha, 6, "OPEN", -20},
                {1, "Process Synchronization", "Revise prerequisite", neha, 3, "OPEN", -12},
                {13, "Graphs (BFS & DFS)", "Peer tutor", arvind, 5, "OPEN", -9},
                {13, "Recursion", "Worksheet", arvind, -2, "OPEN", -16},
                {0, "IP Addressing & Subnetting", "One-to-one doubt session", arvind, 4, "OPEN", -6},
                {7, "Deadlocks", "Worksheet", neha, -10, "DONE", -30},
                {5, "Virtual Memory & Page Replacement", "Video lesson", neha, 8, "OPEN", -4},
        };
        for (Object[] p : plans) {
            User st = students.get((int) p[0]);
            Topic t = null;
            for (Topic x : db.topics) if (x.name.equals(p[1])) t = x;
            SupportPlan sp = new SupportPlan();
            sp.id = db.nextId("P");
            sp.studentId = st.id;
            sp.topicId = t.id;
            sp.action = (String) p[2];
            sp.createdBy = ((User) p[3]).name;
            sp.dueDate = today.plusDays((int) p[4]).toString();
            sp.status = (String) p[5];
            sp.createdAt = today.plusDays((int) p[6]).atTime(11, 0).toString();
            sp.note = "Flagged by the detector.";
            double m = mastery.get(st.id + "|" + t.id);
            sp.indexAtStart = Math.round((1 - m) * 900) / 10.0;
            if (sp.status.equals("DONE")) sp.doneAt = today.minusDays(9).atTime(16, 0).toString();
            db.plans.add(sp);
        }

        db.log("System", "Demo data created", db.users.size() + " logins, " + db.questions.size() + " questions, " + db.attempts.size() + " attempts");
    }

    private static List<Question> testQuestions(DataStore db, List<Topic> topics, Random rnd) {
        List<Question> out = new ArrayList<>();
        for (Topic t : topics) {
            List<Question> qs = new ArrayList<>();
            for (Question q : db.questions) if (q.topicId.equals(t.id)) qs.add(q);
            Collections.shuffle(qs, rnd);
            out.addAll(qs.subList(0, Math.min(2, qs.size())));
        }
        return out;
    }

    private static List<Question> practiceQuestions(DataStore db, List<Topic> topics, User st, Map<String, Double> mastery, Random rnd) {
        // practice goes mostly to the weaker topics
        List<Topic> sorted = new ArrayList<>(topics);
        sorted.sort(Comparator.comparingDouble(t -> mastery.get(st.id + "|" + t.id) + rnd.nextGaussian() * 0.1));
        List<Question> out = new ArrayList<>();
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            List<Question> qs = new ArrayList<>();
            for (Question q : db.questions) if (q.topicId.equals(sorted.get(i).id)) qs.add(q);
            Collections.shuffle(qs, rnd);
            out.addAll(qs.subList(0, Math.min(i == 0 ? 4 : 3, qs.size())));
        }
        return out;
    }

    private static String pick(Random rnd, String... options) { return options[rnd.nextInt(options.length)]; }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
