package com.cecil.mail.store;

import java.util.List;

/**
 * Demo emails for a made-up college, Koel Valley Institute of Technology.
 * All names, numbers and addresses are invented (example.com).
 *
 * Each row: { inbox owner, sender name, sender email, subject, body }
 */
public final class SampleEmails {

    private SampleEmails() { }

    public static final List<String[]> ALL = List.of(
        // ---------------- aditi: Student Help Desk ----------------
        new String[]{"aditi", "Priya Sharma", "priya.sharma@example.com", "Hoodie order #45821 arrived torn",
            "Hello,\n\nI am extremely disappointed. My college fest hoodie, order #45821, arrived torn at the sleeve and nobody "
            + "answered the help desk phone for two days. I want a replacement ASAP!!\n\nRegards,\nPriya Sharma"},
        new String[]{"aditi", "Sneha Patel", "sneha.p@example.com", "Can't sign in to the student portal",
            "Hello,\n\nThe student portal shows error 403 when I try to login after resetting my password. "
            + "I have tried two different browsers.\n\nThanks,\nSneha Patel"},
        new String[]{"aditi", "Meena Joshi", "accounts@sunrise.example.com", "Invoice INV-2093 pending",
            "Dear team,\n\nThe payment of Rs 18,500 for invoice INV-2093 (canteen supplies for October) is still pending. "
            + "It was due last week. Kindly clear it at the earliest.\n\nBest regards,\nMeena Joshi\nSunrise Traders"},
        new String[]{"aditi", "Farhan Ali", "farhan.ali@example.com", "MCA admission documents",
            "Hi,\n\nCould you please share the fee details and the list of documents needed for admission to the MCA programme? "
            + "Also, is there a hostel facility for first-year students?\n\nThank you,\nFarhan Ali"},
        new String[]{"aditi", "Kavya Iyer", "kavya.iyer@example.com", "Thank you for the workshop",
            "Hi,\n\nThank you so much for the amazing cyber safety workshop yesterday. The live demo was really helpful "
            + "and our whole class enjoyed it.\n\nWarm regards,\nKavya Iyer"},
        new String[]{"aditi", "Vikram Rao", "hello@growfast.example.com", "Grow your admissions 10x this season",
            "Hello,\n\nWe offer SEO and social media marketing for colleges at a special discount this month. "
            + "Get a free trial today and boost your admissions!\n\nCheers,\nVikram Rao\nGrowFast Digital"},
        new String[]{"aditi", "Nikhil Das", "nikhil.das@example.com", "Re: Fee receipt not generated",
            "Hello,\n\nJust following up on my email from last week. I paid the semester fee on 28 October but the "
            + "fee receipt is still not visible on the portal. Any update?\n\nRegards,\nNikhil Das"},
        new String[]{"aditi", "Ritu Singh", "ritu.singh@example.com", "Wrong marks on my result",
            "Hello,\n\nMy Operating Systems marks show 12 out of 60, but I wrote all the answers and my internal marks were good. "
            + "This is very worrying. Please check urgently, the re-evaluation deadline is tomorrow.\n\nRitu Singh"},
        new String[]{"aditi", "Ankita Roy", "ankita.roy@example.com", "Library fine refund",
            "Dear Sir/Madam,\n\nI was charged twice for the same library fine of Rs 150 (txn 88213). "
            + "Please refund the extra amount to my account.\n\nThank you,\nAnkita Roy"},
        new String[]{"aditi", "Sameer Khan", "sameer.k@example.com", "Wi-Fi not working in hostel block C",
            "Hi,\n\nThe Wi-Fi in hostel block C has not been working since Sunday. We cannot submit online assignments. "
            + "Please help fix it.\n\nThanks,\nSameer Khan"},

        // ---------------- rohan: Office of the Head of Department ----------------
        new String[]{"rohan", "Rahul Verma", "rahul.verma@example.com", "Quick sync on Monday?",
            "Hi,\n\nAre you free for a call next Monday at 11:30 am to go over the lab timetable? "
            + "I'd like to finalise it before the semester starts.\n\nThanks,\nRahul Verma"},
        new String[]{"rohan", "Ankit Kumar", "ankit.k@example.com", "Leave application",
            "Respected Sir,\n\nI request leave from 12 November to 14 November for my cousin's wedding. "
            + "I will complete my pending tasks before I go.\n\nRegards,\nAnkit Kumar"},
        new String[]{"rohan", "Arjun Mehta", "arjun.m@example.com", "Project report submission",
            "Sir,\n\nI was unwell last week and could not complete the project report. Could I submit it by Friday? "
            + "Most of the coding part is already done.\n\nRegards,\nArjun Mehta"},
        new String[]{"rohan", "Pooja Nair", "pooja.nair@example.com", "Application for teaching assistant role",
            "Dear Sir,\n\nI am a final year M.Sc. IT student and would like to apply for the teaching assistant position in the "
            + "IT department. Please find my resume attached.\n\nRegards,\nPooja Nair"},
        new String[]{"rohan", "Dr. Sunita Rao", "s.rao@example.com", "Thank you for hosting the seminar",
            "Dear Rohan,\n\nThank you for the warm welcome and the excellent arrangements at the AI seminar. "
            + "The students asked great questions.\n\nWarm regards,\nDr. Sunita Rao"},
        new String[]{"rohan", "Karan Gupta", "karan.g@example.com", "Any update on the lab equipment request?",
            "Hello,\n\nI am writing to check the status of the lab equipment request I sent two weeks ago. "
            + "Still waiting for your reply.\n\nThanks,\nKaran Gupta"},
        new String[]{"rohan", "Neha Gupta", "neha.gupta@example.com", "Meeting about the semester project",
            "Hi Rohan,\n\nCould we meet on Thursday at 4 pm to discuss the semester project groups? "
            + "I can come to the department office.\n\nBest,\nNeha Gupta"},
        new String[]{"rohan", "Deepak Yadav", "deepak.y@example.com", "Sick leave today",
            "Sir,\n\nI am not feeling well and need sick leave today and tomorrow. I will share the medical certificate "
            + "when I return.\n\nDeepak Yadav"},

        // ---------------- admin: Principal's office ----------------
        new String[]{"admin", "Manoj Tiwari", "manoj.t@example.com", "Sponsorship for Tech Fest 2026",
            "Hello,\n\nWe would love to explore a partnership with your college. Our company would like to sponsor the "
            + "upcoming tech fest. Could we discuss the details?\n\nRegards,\nManoj Tiwari"},
        new String[]{"admin", "Parents Association", "pta@example.com", "Request for meeting with the Principal",
            "Respected Madam,\n\nThe Parents Association would like to schedule a meeting with you on 20 November "
            + "to discuss hostel safety. Please let us know a suitable time.\n\nRegards,\nAnil Saxena"});

    /**
     * Older emails used only to build realistic history (they are already answered).
     * { owner, sender name, sender email, subject, body }
     */
    public static final List<String[]> HISTORY = List.of(
        new String[]{"aditi", "Rohit Jain", "rohit.j@example.com", "Bus pass not received", "Hello, I paid for the bus pass on 3 October but have not received it yet. Could you please check?\n\nThanks,\nRohit Jain"},
        new String[]{"aditi", "Simran Kaur", "simran.k@example.com", "Hostel room AC broken", "Hi, the AC in room 214 has been broken for a week and the warden has not responded. Very bad experience.\n\nSimran Kaur"},
        new String[]{"aditi", "Aman Verma", "aman.v@example.com", "Scholarship form details", "Dear Sir, kindly share the eligibility criteria and the last date for the merit scholarship form.\n\nRegards,\nAman Verma"},
        new String[]{"aditi", "Tanya Bose", "tanya.b@example.com", "Unable to download admit card", "The admit card download button gives an error message. My exam is on Monday.\n\nTanya Bose"},
        new String[]{"aditi", "Harsh Patel", "harsh.p@example.com", "Payment done for transcript", "I have made the payment of Rs 500 for my transcript, please confirm the receipt. Transaction ID TXN-55120.\n\nHarsh Patel"},
        new String[]{"aditi", "Isha Malhotra", "isha.m@example.com", "Thanks for the quick help", "Thank you for resolving my portal issue so quickly yesterday. Really appreciate it!\n\nIsha"},
        new String[]{"aditi", "Gaurav Sinha", "gaurav.s@example.com", "Checking in on my certificate", "Following up on my request for the bonafide certificate. Any update?\n\nGaurav Sinha"},
        new String[]{"aditi", "EduPrint Solutions", "sales@eduprint.example.com", "Bulk printing offer", "Exclusive discount on ID card printing for colleges, limited time only. Get 30 percent off.\n\nTeam EduPrint"},
        new String[]{"aditi", "Lakshmi Menon", "lakshmi.m@example.com", "Canteen food quality", "I want to complain about the canteen food. It was cold and stale twice this week.\n\nLakshmi Menon"},
        new String[]{"aditi", "Varun Shetty", "varun.s@example.com", "Exam timetable", "Could you please share the exam timetable for the B.Sc. IT third semester?\n\nVarun Shetty"},
        new String[]{"aditi", "Divya Pillai", "divya.p@example.com", "Refund for cancelled trip", "Kindly process my refund of Rs 2,000 for the cancelled industrial visit.\n\nDivya Pillai"},
        new String[]{"aditi", "Abhishek Rana", "abhishek.r@example.com", "Email account locked", "My college email account is locked after the password change. Please help me access it.\n\nAbhishek"},
        new String[]{"rohan", "Sonal Dubey", "sonal.d@example.com", "Half day leave tomorrow", "Requesting approval for a half day leave tomorrow for a bank visit.\n\nSonal Dubey"},
        new String[]{"rohan", "Mohit Arora", "mohit.a@example.com", "Lab record extension", "I request a short extension for the lab record submission as my printer broke.\n\nMohit Arora"},
        new String[]{"rohan", "Prakash Rao", "prakash.r@example.com", "Department meeting", "Can we schedule the department meeting next week to review results?\n\nPrakash"},
        new String[]{"rohan", "Shruti Ghosh", "shruti.g@example.com", "Internship opening", "I am interested in the research internship posted on the notice board. Resume attached.\n\nShruti Ghosh"},
        new String[]{"rohan", "Ajay Mishra", "ajay.m@example.com", "Thank you", "Grateful for your guidance throughout the project. Thank you for your valuable time.\n\nAjay"},
        new String[]{"rohan", "Rekha Sharma", "rekha.s@example.com", "Reminder: syllabus copy", "Gentle reminder about the syllabus copy you promised to share. Still waiting.\n\nRekha"},
        new String[]{"rohan", "Yash Thakur", "yash.t@example.com", "Assignment deadline", "Due to technical problems I could not submit on time. Please extend the deadline.\n\nYash Thakur"},
        new String[]{"rohan", "Nisha Jha", "nisha.j@example.com", "Vacation leave December", "Please approve my vacation leave for the first week of December.\n\nNisha Jha"});
}
