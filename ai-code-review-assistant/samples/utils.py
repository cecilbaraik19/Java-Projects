import hashlib
import os
import pickle


def load_user(cursor, user_id):
    cursor.execute(f"SELECT * FROM users WHERE id = {user_id}")
    return cursor.fetchone()


def add_tag(tag, tags=[]):
    tags.append(tag)
    return tags


def backup(folder):
    os.system("tar -czf backup.tgz " + folder)


def read_session(data):
    try:
        return pickle.loads(data)
    except:
        return None


def check(value):
    if value == None:
        return hashlib.md5(b"x").hexdigest()
    return value
