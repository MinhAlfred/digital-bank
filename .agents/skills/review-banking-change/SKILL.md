---
name: review-banking-change
description: Review thay đổi mã nguồn digital-bank để tìm lỗi nghiệp vụ ngân hàng, transaction, phân quyền và schema. Dùng khi được yêu cầu review diff hoặc PR; không tự sửa hay commit trong quá trình review.
---

# Review thay đổi ngân hàng

## Chọn thay đổi cần xem

- Đọc hướng dẫn áp dụng, `git status` và diff được yêu cầu. Nếu người dùng yêu cầu review thay đổi hiện tại, xem cả staged và unstaged; kiểm tra file mới liên quan.
- Với PR, xác định base branch và đọc diff từ merge base. Theo luồng gọi và test liên quan để hiểu hành vi trước và sau thay đổi.
- Phân biệt lỗi được đưa vào bởi diff với thiếu sót đã tồn tại; không suy đoán chức năng chưa được triển khai.

## Kiểm tra theo phạm vi ảnh hưởng

- Tài khoản: kiểm tra quyền sở hữu, điều kiện khách hàng, trạng thái ACTIVE/FROZEN/CLOSED và tính nhất quán của lịch sử trạng thái.
- Tiền và persistence: kiểm tra minor units, tràn số, ngoại lệ số dư của SYSTEM, optimistic locking và rollback khi một bước ghi thất bại. Với thao tác có thể lặp lại như chuyển tiền, kiểm tra nguy cơ ghi nhận hai lần nếu luồng đó tồn tại.
- Xác thực: kiểm tra gateway loại header danh tính giả mạo, backend xác minh JWT và endpoint công khai không vô tình mở quyền. Không đưa token hoặc secret vào báo cáo.
- Tích hợp và schema: kiểm tra contract giữa service, migration tương thích dữ liệu cũ và việc xử lý lỗi từ service phụ thuộc. Chỉ đánh giá Kafka khi thay đổi thực sự dùng message.
- Test: kiểm tra assertion có bắt được lỗi đang xét; test context hoặc test rỗng không chứng minh quy tắc nghiệp vụ.

## Trình bày kết quả

Ưu tiên phát hiện ảnh hưởng lớn. Mỗi phát hiện cần mức độ, vị trí file/dòng, điều kiện tái hiện, hậu quả và hướng sửa ngắn gọn. Không nâng sở thích định dạng thành lỗi chức năng.

Nếu không có phát hiện đủ bằng chứng, nói rõ và liệt kê khoảng trống kiểm chứng còn liên quan. Nêu lệnh kiểm tra đã chạy hoặc lý do chưa chạy. Chỉ sửa code khi người dùng yêu cầu thêm.

Ví dụ yêu cầu: “Review thay đổi mở tài khoản, tập trung quyền sở hữu và transaction”.
