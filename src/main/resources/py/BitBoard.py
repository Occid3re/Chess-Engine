import tkinter as tk

def print_chessboard_from_number(number, base):
    try:
        number = int(number, base)
        if number < 0:
            number = number + (1 << 64)  # Convert to two's complement
        binary_string = bin(number & 0xFFFFFFFFFFFFFFFF)[2:].zfill(64)
        chessboard = [["0" for _ in range(8)] for _ in range(8)]
        for i, bit in enumerate(binary_string):
            if bit == "1":
                rank = i // 8
                file = 7 - (i % 8)
                chessboard[rank][file] = "1"
        return chessboard
    except ValueError:
        return [["Invalid" for _ in range(8)] for _ in range(8)]

entry_update_allowed = True

def cell_clicked(row, col):
    current_text = labels[row][col].cget("text")
    labels[row][col].config(text='0' if current_text == '1' else '1', fg='black' if current_text == '1' else 'red')
    update_chessboard_state()
    update_entry_from_chessboard()

def update_chessboard():
    global entry_update_allowed
    if entry_update_allowed:
        if entry_hex.get():
            try:
                number = int(entry_hex.get(), 16)
                valid = True
            except ValueError:
                valid = False
        elif entry_dec.get():
            try:
                number = int(entry_dec.get(), 10)
                valid = True
            except ValueError:
                valid = False
        elif entry_bin.get():
            try:
                number = int(entry_bin.get(), 2)
                valid = True
            except ValueError:
                valid = False
        else:
            valid = False

        if valid:
            chessboard = print_chessboard_from_number(str(number), 10)
            for row in range(8):
                for col in range(8):
                    text = chessboard[row][col]
                    color = 'red' if text == '1' else 'black'
                    labels[row][col].config(text=text, fg=color)

def update_chessboard_from_entries():
    global entry_update_allowed
    entry_update_allowed = True
    update_chessboard()

def update_chessboard_state():
    binary_string = ''
    for row in range(8):  # Start from the bottom row (h1) and go to the top row (a8)
        for col in range(7, -1, -1):  # Traverse each column from right to left
            binary_string += '1' if labels[row][col].cget("text") == '1' else '0'
    return int(binary_string, 2)


def update_entry_from_chessboard():
    number = update_chessboard_state()
    binary_str = bin(number)[2:].zfill(64)
    binary_label.config(text=binary_str)  # Update the binary number label
    
    # Update the input fields, considering negative numbers
    if number >= 0:
        entry_bin.delete(0, tk.END)
        entry_bin.insert(0, binary_str)
    else:
        entry_bin.delete(0, tk.END)
        entry_bin.insert(0, '-' + binary_str[1:])  # Exclude the negative sign from binary
    
    entry_dec.delete(0, tk.END)
    entry_dec.insert(0, str(number))
    entry_hex.delete(0, tk.END)
    entry_hex.insert(0, hex(number)[2:])

def on_entry_click(event):
    global entry_update_allowed
    entry_update_allowed = False

def create_chessboard_frame(parent):
    frame = tk.Frame(parent)
    for row in range(8):
        for col in range(8):
            label = tk.Label(frame, text='0', width=4, height=2, font=('Arial', 20))
            label.grid(row=row, column=col)
            label.bind("<Button-1>", lambda event, r=row, c=col: cell_clicked(r, c))
            labels[row].append(label)
    return frame

def create_update_button(parent):
    update_button = tk.Button(parent, text='Update Chessboard', command=update_chessboard_from_entries)
    update_button.pack()

# Set up the main window
root = tk.Tk()
root.title("Bitboard Generator")

# Create a label for displaying the binary number
binary_label = tk.Label(root, text='', font=('Arial', 12))
binary_label.pack()

# Create labels and input fields for binary, decimal (long), and hexadecimal
tk.Label(root, text='Binary:', font=('Arial', 12)).pack()
entry_bin = tk.Entry(root, font=('Arial', 14))
entry_bin.pack()

tk.Label(root, text='Decimal:', font=('Arial', 12)).pack()
entry_dec = tk.Entry(root, font=('Arial', 14))
entry_dec.pack()

tk.Label(root, text='Hexadecimal:', font=('Arial', 12)).pack()
entry_hex = tk.Entry(root, font=('Arial', 14))
entry_hex.pack()

# Create an Update Chessboard button
create_update_button(root)

# Chessboard display area
labels = [[], [], [], [], [], [], [], []]
chessboard_frame = create_chessboard_frame(root)
chessboard_frame.pack()

# Start the application
root.mainloop()
