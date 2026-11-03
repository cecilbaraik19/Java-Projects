package com.cecil.mail.ai;

import java.util.List;

/**
 * Two held-out test sets. The model NEVER trains on either of them.
 *
 *  ALL   ("development set", 44 emails, 4 per intent) - used while building the project.
 *        Some cue words were added after looking at its mistakes, so its score is a bit optimistic.
 *  FINAL ("final set", 33 emails, 3 per intent) - written last and checked only once.
 *        Nothing was changed after seeing its result, so it is the honest score to quote.
 *
 * Each row: { expected intent, subject, body }
 */
public final class TestEmails {

    private TestEmails() { }

    public static final List<String[]> ALL = List.of(
        new String[]{"MEETING_REQUEST", "Catch up", "Do you have thirty minutes on Wednesday to talk about the budget?"},
        new String[]{"MEETING_REQUEST", "Demo session", "We want to book a slot with you next Tuesday for a product walkthrough."},
        new String[]{"MEETING_REQUEST", "Parent teacher meet", "Can I come and meet you in the staff room after the lecture tomorrow?"},
        new String[]{"MEETING_REQUEST", "Planning", "Let's get the whole team on a Google Meet to plan the annual day."},

        new String[]{"COMPLAINT", "Very unhappy", "The laptop I bought from your store is faulty and your staff refused to help me."},
        new String[]{"COMPLAINT", "Bad food", "The mess food was stale again today, this is not acceptable at all."},
        new String[]{"COMPLAINT", "Rude behaviour", "I was treated badly at the reception counter and I want to report it."},
        new String[]{"COMPLAINT", "Missing item", "Half of my order is missing and the box was open when it arrived."},

        new String[]{"SUPPORT_ISSUE", "OTP not coming", "I do not get the OTP on my phone when I try to sign in to the app."},
        new String[]{"SUPPORT_ISSUE", "Upload fails", "Every time I upload my assignment PDF it shows file upload failed."},
        new String[]{"SUPPORT_ISSUE", "Printer issue", "The lab printer is not connecting to any computer, it shows offline."},
        new String[]{"SUPPORT_ISSUE", "Page blank", "After clicking results the page goes blank and nothing loads."},

        new String[]{"INFO_REQUEST", "Course details", "Can you tell me the duration and the fees of the data science certificate course?"},
        new String[]{"INFO_REQUEST", "Hostel info", "I would like to know if hostel rooms are available for girls and what the charges are."},
        new String[]{"INFO_REQUEST", "Placement stats", "Please share last year's placement record and the list of companies."},
        new String[]{"INFO_REQUEST", "Bus routes", "What are the college bus routes and timings from Ranchi city?"},

        new String[]{"FOLLOW_UP", "Second reminder", "I sent you the form two weeks back and have not received any response yet."},
        new String[]{"FOLLOW_UP", "Status?", "Hi again, wanted to know where things stand with my transfer certificate request."},
        new String[]{"FOLLOW_UP", "Pending", "Just a quick reminder that I am still waiting to hear back about my application."},
        new String[]{"FOLLOW_UP", "Nudge", "Bumping this up in your inbox, could you update me when possible?"},

        new String[]{"JOB_APPLICATION", "Job enquiry", "I have two years of Java experience and want to apply for the backend developer job."},
        new String[]{"JOB_APPLICATION", "Interview slot", "Thank you for selecting me for the interview, I confirm I will attend on Monday."},
        new String[]{"JOB_APPLICATION", "Summer intern", "I am looking for a summer internship in your IT team and have attached my CV."},
        new String[]{"JOB_APPLICATION", "Offer letter", "We are happy to offer you the post of lab assistant starting next month."},

        new String[]{"PAYMENT_INVOICE", "Bill attached", "Attached is the bill for the October maintenance work, amount Rs 42,000."},
        new String[]{"PAYMENT_INVOICE", "Refund", "I cancelled my registration, when will I get my money back?"},
        new String[]{"PAYMENT_INVOICE", "Dues", "Our records show that your hostel dues are unpaid, please pay before the 15th."},
        new String[]{"PAYMENT_INVOICE", "Transaction", "The amount got debited from my bank but the portal still shows payment failed."},

        new String[]{"LEAVE_REQUEST", "Out of station", "I have to go home for a family function, kindly grant me two days off."},
        new String[]{"LEAVE_REQUEST", "Doctor visit", "I need to visit the doctor tomorrow morning, so I will not come to the office."},
        new String[]{"LEAVE_REQUEST", "Maternity", "I would like to apply for maternity leave starting from January."},
        new String[]{"LEAVE_REQUEST", "Absent", "Please excuse my absence from classes on Monday and Tuesday as I am travelling."},

        new String[]{"DEADLINE_EXTENSION", "More time", "Could you allow one more week for the research paper? The survey data came late."},
        new String[]{"DEADLINE_EXTENSION", "Late submission", "Is it okay if I hand in the case study on Saturday instead of Thursday?"},
        new String[]{"DEADLINE_EXTENSION", "Delay in delivery", "The vendor is late, so we need to shift the project due date by ten days."},
        new String[]{"DEADLINE_EXTENSION", "Extension please", "My laptop crashed and I lost my work, may I get an extension for the assignment?"},

        new String[]{"THANK_YOU", "Grateful", "Many thanks for writing the recommendation letter for me, it means a lot."},
        new String[]{"THANK_YOU", "Great event", "Thanks to you and the team for organising such a wonderful fest."},
        new String[]{"THANK_YOU", "Appreciation", "I really appreciate how patiently you explained everything to my parents."},
        new String[]{"THANK_YOU", "Thank you", "Thank you for the quick reply and the kind support during admission."},

        new String[]{"SALES_PITCH", "New product", "Introducing our smart attendance system, book a free demo for your college today."},
        new String[]{"SALES_PITCH", "Partnership", "We would like to collaborate with your institute for a coding bootcamp."},
        new String[]{"SALES_PITCH", "Limited offer", "Buy one get one free on all annual subscriptions, offer ends Sunday."},
        new String[]{"SALES_PITCH", "Services", "Our agency provides affordable website design and hosting for schools."});

    public static final List<String[]> FINAL = List.of(
        new String[]{"MEETING_REQUEST", "Review", "Can the three of us get together on Tuesday afternoon to go over the audit findings?"},
        new String[]{"MEETING_REQUEST", "Visit", "I will be on campus next week and would love to drop by your office for a chat."},
        new String[]{"MEETING_REQUEST", "Discussion", "Please let me know when you can spare some time to discuss the new elective."},
        new String[]{"COMPLAINT", "Not okay", "The hall ticket counter made us stand in the sun for three hours with no water."},
        new String[]{"COMPLAINT", "Shoddy work", "The painting in our hostel room was done badly and the paint is peeling off."},
        new String[]{"COMPLAINT", "Upset", "I am really annoyed that my certificate has spelling mistakes in my name again."},
        new String[]{"SUPPORT_ISSUE", "Cannot access", "The e-library keeps saying session expired whenever I open a journal."},
        new String[]{"SUPPORT_ISSUE", "Screen frozen", "My lab PC froze during the online quiz and now it will not restart."},
        new String[]{"SUPPORT_ISSUE", "Zoom link", "The link for the online class opens but the audio does not work."},
        new String[]{"INFO_REQUEST", "Query", "Does the college offer any evening courses in digital marketing?"},
        new String[]{"INFO_REQUEST", "Canteen", "May I know the canteen menu and its opening hours on Sundays?"},
        new String[]{"INFO_REQUEST", "Transfer", "What is the process to transfer from another university into second year?"},
        new String[]{"FOLLOW_UP", "Pending reply", "I had written to you about my migration certificate ten days ago, kindly respond."},
        new String[]{"FOLLOW_UP", "Again", "Resending my last message as I think it got lost, please have a look."},
        new String[]{"FOLLOW_UP", "Waiting", "It has been a month since I applied for the ID card and there is no news."},
        new String[]{"JOB_APPLICATION", "Faculty post", "I hold a PhD in computer science and would like to join your faculty."},
        new String[]{"JOB_APPLICATION", "Selection", "Congratulations, you have been selected for the junior developer post."},
        new String[]{"JOB_APPLICATION", "Openings", "Are there any part time jobs for students in the college office?"},
        new String[]{"PAYMENT_INVOICE", "Quotation and bill", "Please find our bill for the furniture supplied, payable within thirty days."},
        new String[]{"PAYMENT_INVOICE", "Charged extra", "I was asked to pay the exam fee twice, kindly return the extra money."},
        new String[]{"PAYMENT_INVOICE", "Stipend", "My internship stipend for September has not come to my account yet."},
        new String[]{"LEAVE_REQUEST", "Off on Monday", "I have a court hearing on Monday so I will not be able to come to work."},
        new String[]{"LEAVE_REQUEST", "Medical", "The doctor has advised bed rest for a week, please treat this as my leave request."},
        new String[]{"LEAVE_REQUEST", "Wedding", "My brother is getting married, so I would like to be away from 2 to 6 December."},
        new String[]{"DEADLINE_EXTENSION", "Need time", "We are short of two days to finish the robot, can the final demo move to Monday?"},
        new String[]{"DEADLINE_EXTENSION", "Report delay", "My survey responses are still coming in, could the report be due later?"},
        new String[]{"DEADLINE_EXTENSION", "Assignment", "Can I send the mini project next week because of my cousin's wedding?"},
        new String[]{"THANK_YOU", "Thanks", "Your notes made the exam so much easier, thank you very much."},
        new String[]{"THANK_YOU", "Kindness", "I am deeply grateful to you for helping my family during the emergency."},
        new String[]{"THANK_YOU", "Great job", "Just wanted to say a big thanks for the smooth conduct of the sports meet."},
        new String[]{"SALES_PITCH", "Software", "Our school ERP software can automate fees and attendance, shall we show you?"},
        new String[]{"SALES_PITCH", "Uniforms", "We make high quality blazers and ties for institutions at factory prices."},
        new String[]{"SALES_PITCH", "Ads", "Reach thousands of students by advertising on our campus radio channel."});
}
