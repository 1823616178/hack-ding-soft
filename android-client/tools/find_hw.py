import os

for root, dirs, files in os.walk('../decompiled'):
    for f in files:
        if f.endswith('.java'):
            p = os.path.join(root, f)
            try:
                with open(p, 'r', encoding='utf-8', errors='ignore') as fp:
                    content = fp.read()
                if '182.92.162.37' in content or 'hwKeyCode' in content or 'HwGetKeyCode' in content or '8888' in content:
                    print('Match:', p)
            except Exception as e:
                pass
