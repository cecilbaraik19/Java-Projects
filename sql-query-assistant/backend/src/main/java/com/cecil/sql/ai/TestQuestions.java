package com.cecil.sql.ai;

import java.util.List;

/**
 * Questions with the correct ("gold") SQL, used to measure the English-to-SQL engine honestly.
 *
 * DEV   – used while building the engine. Rules were improved after looking at its mistakes,
 *         so its score is optimistic.
 * FINAL – written BEFORE the engine, with different wording, and run only once at the end.
 *         Nothing was changed after seeing its score. This is the number to quote.
 *
 * A question passes when the engine's result has the same rows as the gold result
 * (extra columns are allowed, row order is ignored). This is called "execution accuracy".
 */
public final class TestQuestions {
    private TestQuestions() {}

    public record Case(String db, String question, String goldSql) {}

    private static Case c(String q, String sql) { return new Case("koel_college", q, sql); }
    private static Case g(String q, String sql) { return new Case("ghar_store", q, sql); }

    public static final List<Case> DEV = List.of(
            c("How many students are there?", "SELECT COUNT(*) FROM students"),
            c("List all students from Ranchi", "SELECT name FROM students WHERE city = 'Ranchi'"),
            c("Show students with CGPA above 9", "SELECT name FROM students WHERE cgpa > 9"),
            c("How many female students are there", "SELECT COUNT(*) FROM students WHERE gender = 'Female'"),
            c("Average CGPA of students", "SELECT AVG(cgpa) FROM students"),
            c("Number of students in each department",
                    "SELECT d.name, COUNT(*) FROM students s JOIN departments d ON d.dept_id = s.dept_id GROUP BY d.name"),
            c("Top 5 students by CGPA", "SELECT name FROM students ORDER BY cgpa DESC LIMIT 5"),
            c("Which student has the highest CGPA?", "SELECT name FROM students ORDER BY cgpa DESC LIMIT 1"),
            c("What is the highest CGPA?", "SELECT MAX(cgpa) FROM students"),
            c("Students in Computer Science department",
                    "SELECT s.name FROM students s JOIN departments d ON d.dept_id = s.dept_id WHERE d.name = 'Computer Science'"),
            c("How many hostellers are there", "SELECT COUNT(*) FROM students WHERE hosteller = 'Yes'"),
            c("Average CGPA per city", "SELECT city, AVG(cgpa) FROM students GROUP BY city"),
            c("List students in semester 3", "SELECT name FROM students WHERE semester = 3"),
            c("How many students were admitted in 2024", "SELECT COUNT(*) FROM students WHERE admission_year = 2024"),
            c("Show all departments", "SELECT name FROM departments"),
            c("List teachers in the Mechanical department",
                    "SELECT f.name FROM faculty f JOIN departments d ON d.dept_id = f.dept_id WHERE d.name = 'Mechanical Engineering'"),
            c("How many professors are there", "SELECT COUNT(*) FROM faculty WHERE designation = 'Professor'"),
            c("Courses with 4 credits", "SELECT title FROM courses WHERE credits = 4"),
            c("Show courses with their teacher names",
                    "SELECT c.title, f.name FROM courses c JOIN faculty f ON f.faculty_id = c.faculty_id"),
            c("Average marks in DBMS",
                    "SELECT AVG(e.marks) FROM enrollments e JOIN courses c ON c.course_id = e.course_id WHERE c.title = 'Database Management Systems'"),
            c("How many students failed", "SELECT COUNT(DISTINCT student_id) FROM enrollments WHERE grade = 'F'"),
            c("Total fees collected", "SELECT SUM(amount) FROM fee_payments"),
            c("Total amount paid by UPI", "SELECT SUM(amount) FROM fee_payments WHERE mode = 'UPI' AND status = 'Paid'"),
            c("How many payments are pending", "SELECT COUNT(*) FROM fee_payments WHERE status = 'Pending'"),
            c("Total fees by payment mode", "SELECT mode, SUM(amount) FROM fee_payments GROUP BY mode"),
            c("Students from Ranchi or Jamshedpur", "SELECT name FROM students WHERE city IN ('Ranchi', 'Jamshedpur')"),
            c("Students not from Ranchi", "SELECT name FROM students WHERE city != 'Ranchi'"),
            c("Students with CGPA between 8 and 9", "SELECT name FROM students WHERE cgpa BETWEEN 8 AND 9"),
            c("Which department has the most students",
                    "SELECT d.name FROM students s JOIN departments d ON d.dept_id = s.dept_id GROUP BY d.name ORDER BY COUNT(*) DESC LIMIT 1"),
            c("Students whose name starts with A", "SELECT name FROM students WHERE name LIKE 'A%'"),
            c("Enrollments with attendance below 60%", "SELECT enrollment_id FROM enrollments WHERE attendance < 60"),
            c("How many students in each semester", "SELECT semester, COUNT(*) FROM students GROUP BY semester"),
            c("Lowest marks in Java", "SELECT MIN(e.marks) FROM enrollments e JOIN courses c ON c.course_id = e.course_id WHERE c.title = 'Java Programming'"),
            c("Number of girls in each department",
                    "SELECT d.name, COUNT(*) FROM students s JOIN departments d ON d.dept_id = s.dept_id WHERE s.gender = 'Female' GROUP BY d.name"),
            c("Show students sorted by name", "SELECT name FROM students ORDER BY name"),
            g("How many customers do we have", "SELECT COUNT(*) FROM customers"),
            g("Most expensive product", "SELECT name FROM products ORDER BY price DESC LIMIT 1"),
            g("Products under 500 rupees", "SELECT name FROM products WHERE price < 500"),
            g("Number of products in each category", "SELECT category, COUNT(*) FROM products GROUP BY category"),
            g("How many orders were cancelled", "SELECT COUNT(*) FROM orders WHERE status = 'Cancelled'"),
            g("Customers from Kolkata", "SELECT name FROM customers WHERE city = 'Kolkata'"),
            g("Orders paid by cash on delivery", "SELECT order_id FROM orders WHERE payment_mode = 'COD'"),
            g("Products that are out of stock", "SELECT name FROM products WHERE stock = 0"),
            g("Average price of kitchen products", "SELECT AVG(price) FROM products WHERE category = 'Kitchen'"),
            g("Number of orders per status", "SELECT status, COUNT(*) FROM orders GROUP BY status")
    );

    public static final List<Case> FINAL = List.of(
            c("count the students who live in Dhanbad", "SELECT COUNT(*) FROM students WHERE city = 'Dhanbad'"),
            c("give me the names of boys with gpa more than 8.5", "SELECT name FROM students WHERE gender = 'Male' AND cgpa > 8.5"),
            c("what is the mean cgpa of hostellers", "SELECT AVG(cgpa) FROM students WHERE hosteller = 'Yes'"),
            c("department wise count of teachers",
                    "SELECT d.name, COUNT(*) FROM faculty f JOIN departments d ON d.dept_id = f.dept_id GROUP BY d.name"),
            c("show the 3 best students", "SELECT name FROM students ORDER BY cgpa DESC LIMIT 3"),
            c("who are the day scholars in ECE",
                    "SELECT s.name FROM students s JOIN departments d ON d.dept_id = s.dept_id WHERE s.hosteller = 'No' AND d.name = 'Electronics and Communication'"),
            c("list subjects taught in semester 4", "SELECT title FROM courses WHERE semester = 4"),
            c("maximum marks scored in Operating Systems",
                    "SELECT MAX(e.marks) FROM enrollments e JOIN courses c ON c.course_id = e.course_id WHERE c.title = 'Operating Systems'"),
            c("how many assistant professors work in civil",
                    "SELECT COUNT(*) FROM faculty f JOIN departments d ON d.dept_id = f.dept_id WHERE f.designation = 'Assistant Professor' AND d.name = 'Civil Engineering'"),
            c("total hostel fee paid", "SELECT SUM(amount) FROM fee_payments WHERE purpose = 'Hostel'"),
            c("how many fee payments failed", "SELECT COUNT(*) FROM fee_payments WHERE status = 'Failed'"),
            c("students of 2025 batch", "SELECT name FROM students WHERE admission_year = 2025"),
            c("which city do most students come from", "SELECT city FROM students GROUP BY city ORDER BY COUNT(*) DESC LIMIT 1"),
            c("average attendance for each course",
                    "SELECT c.title, AVG(e.attendance) FROM enrollments e JOIN courses c ON c.course_id = e.course_id GROUP BY c.title"),
            c("students with a cgpa of at least 9", "SELECT name FROM students WHERE cgpa >= 9"),
            c("female teachers", "SELECT name FROM faculty WHERE gender = 'Female'"),
            c("how many courses have 3 credits", "SELECT COUNT(*) FROM courses WHERE credits = 3"),
            c("list the students who are not in the hostel", "SELECT name FROM students WHERE hosteller = 'No'"),
            c("number of students from each city", "SELECT city, COUNT(*) FROM students GROUP BY city"),
            c("lowest cgpa in computer science",
                    "SELECT MIN(s.cgpa) FROM students s JOIN departments d ON d.dept_id = s.dept_id WHERE d.name = 'Computer Science'"),
            g("cheapest item in the lighting category", "SELECT name FROM products WHERE category = 'Lighting' ORDER BY price ASC LIMIT 1"),
            g("how many wholesale customers are there", "SELECT COUNT(*) FROM customers WHERE segment = 'Wholesale'"),
            g("list products costing more than 3000", "SELECT name FROM products WHERE price > 3000"),
            g("customers in Bihar", "SELECT name FROM customers WHERE state = 'Bihar'"),
            g("count of returned orders", "SELECT COUNT(*) FROM orders WHERE status = 'Returned'"),
            g("average product price per category", "SELECT category, AVG(price) FROM products GROUP BY category"),
            g("show 5 most expensive products", "SELECT name FROM products ORDER BY price DESC LIMIT 5"),
            g("orders paid using UPI that were delivered", "SELECT order_id FROM orders WHERE payment_mode = 'UPI' AND status = 'Delivered'"),
            g("how many customers in each state", "SELECT state, COUNT(*) FROM customers GROUP BY state"),
            g("products with stock less than 10", "SELECT name FROM products WHERE stock < 10")
    );
}
